package com.example.voiceassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Runs MS in the background with a floating bubble.
 * Tap bubble = talk now. Long-press = always-listening ("Hey MS" / "MS") on or off.
 */
class AssistantService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.voiceassistant.STOP"
        const val PREFS = "voice_prefs"
        const val KEY_TAMIL = "use_tamil"
        const val KEY_WAKE_TH = "wake_threshold"
        const val DEFAULT_WAKE_TH = 0.80f
        const val SHOW_SCORE = true
        private const val CHANNEL_ID = "ms_assistant"
        private const val NOTIF_ID = 1

        private const val STATE_OFF = 0
        private const val STATE_WAKE = 1
        private const val STATE_HEARING = 2
        private const val STATE_SPEAKING = 3

        private val COLOR_OFF = Color.parseColor("#9E9E9E")
        private val COLOR_WAKE = Color.parseColor("#43A047")
        private val COLOR_HEARING = Color.parseColor("#FB8C00")
        private val COLOR_SPEAKING = Color.parseColor("#1E88E5")

        @Volatile var running = false
    }

    private lateinit var verifier: SpeakerVerifier
    private lateinit var wm: WindowManager
    private var bubble: ImageView? = null
    private var bubbleBg: GradientDrawable? = null

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var tamilActive = false
    @Volatile private var appliedTamil = -1
    @Volatile private var warnedNoTamil = false

    private var recognizer: SpeechRecognizer? = null
    private var tone: ToneGenerator? = null
    @Volatile private var sttResults: List<String> = emptyList()
    @Volatile private var sttLatch: CountDownLatch? = null

    private var focusReq: AudioFocusRequest? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var alwaysOn = false
    @Volatile private var talkRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        running = true
        verifier = SpeakerVerifier(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (e: Exception) {
            null
        }
        initTts()
        initRecognizer()
        showBubble()
        ensureVolume()
        worker.execute { mainLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            alwaysOn = false
            removeBubbleNow()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            mainHandler.postDelayed({ Process.killProcess(Process.myPid()) }, 400)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        alwaysOn = false
        removeBubbleNow()
        dropFocus()
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        tone?.release()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun removeBubbleNow() {
        try {
            bubble?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        bubble = null
    }

    // ---------- Foreground notification ----------

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MS assistant", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MS odudhu")
            .setContentText("Bubble tap = pesalam, long-press = Hey MS on/off")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop MS", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- Floating bubble ----------

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val pad = (14 * density).toInt()

        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(COLOR_OFF)

        val view = ImageView(this)
        view.setImageResource(android.R.drawable.ic_btn_speak_now)
        view.background = bg
        view.alpha = 0.6f
        view.setPadding(pad, pad, pad, pad)

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (8 * density).toInt()
        lp.y = (200 * density).toInt()

        view.setOnTouchListener(BubbleTouch(lp))
        wm.addView(view, lp)
        bubble = view
        bubbleBg = bg
        setState(STATE_OFF)
    }

    private inner class BubbleTouch(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var dragged = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                    downTime = System.currentTimeMillis()
                    dragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) dragged = true
                    if (dragged) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        val held = System.currentTimeMillis() - downTime
                        if (held >= 600) toggleAlways() else requestTalk()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun toggleAlways() {
        alwaysOn = !alwaysOn
        toast(if (alwaysOn) "Hey MS / MS listening ON" else "Always listening OFF")
        if (!alwaysOn) setState(STATE_OFF)
    }

    private fun requestTalk() {
        talkRequested = true
    }

    private fun setState(s: Int) {
        mainHandler.post {
            val color = when (s) {
                STATE_WAKE -> COLOR_WAKE
                STATE_HEARING -> COLOR_HEARING
                STATE_SPEAKING -> COLOR_SPEAKING
                else -> COLOR_OFF
            }
            bubbleBg?.setColor(color)
            bubble?.invalidate()
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Main loop (runs on worker thread) ----------

    private fun wakeThreshold(): Float =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_WAKE_TH, DEFAULT_WAKE_TH)

    private fun mainLoop() {
        while (running) {
            if (talkRequested) {
                talkRequested = false
                runCommandFlow()
                continue
            }
            if (!alwaysOn) {
                setState(STATE_OFF)
                try {
                    Thread.sleep(120)
                } catch (e: InterruptedException) {
                    return
                }
                continue
            }

            setState(STATE_WAKE)
            val audio = AudioCapture.recordUtterance(
                waitForSpeechMs = 5000,
                maxSpeechMs = 2500,
                endSilenceMs = 300,
                shouldStop = { !running || !alwaysOn || talkRequested }
            ) ?: continue
            if (!running || !alwaysOn || talkRequested) continue

            val score = verifier.score(audio)
            val th = wakeThreshold()
            if (SHOW_SCORE) {
                toast(String.format(Locale.US, "Voice match %.2f (need %.2f)", score, th))
            }
            if (score >= th) {
                // let the microphone free up before speech recognition starts
                try {
                    Thread.sleep(350)
                } catch (e: InterruptedException) {
                    return
                }
                runCommandFlow()
            }
        }
    }

    private fun runCommandFlow() {
        setState(STATE_HEARING)
        takeFocus()
        beep()
        val candidates = listenForCommands()
        if (candidates.isEmpty()) {
            dropFocus()
            setState(if (alwaysOn) STATE_WAKE else STATE_OFF)
            return
        }

        var chosen: Reply? = null
        var said = candidates[0]
        for (c in candidates) {
            val r = Brain.reply(this, c)
            if (r.understood) {
                chosen = r
                said = c
                break
            }
        }
        val reply = chosen ?: Brain.reply(this, candidates[0])

        applyLanguageIfNeeded()
        val spoken = if (tamilActive) reply.tamil else reply.tanglish
        if (wantTamil() && !tamilActive && !warnedNoTamil) {
            warnedNoTamil = true
            toast("Tamil voice illa. Phone Settings > Text-to-speech > Google > Tamil voice install pannunga.")
        }
        toast("Neenga: $said\nMS: $spoken")

        reply.action?.let { act -> mainHandler.post { act(this@AssistantService) } }
        if (reply.stopListening) alwaysOn = false

        setState(STATE_SPEAKING)
        speakAndWait(spoken)
        dropFocus()
        setState(if (alwaysOn) STATE_WAKE else STATE_OFF)
    }

    private fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 100)
        try {
            Thread.sleep(150)
        } catch (e: InterruptedException) {
        }
    }

    // ---------- Audio focus (pauses songs / videos while MS listens and speaks) ----------

    private fun takeFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(req)
            focusReq = req
        } catch (e: Exception) {
        }
    }

    private fun dropFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            focusReq?.let { am.abandonAudioFocusRequest(it) }
        } catch (e: Exception) {
        }
        focusReq = null
    }

    // ---------- Speech-to-text ----------

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) {
                sttResults = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
                sttLatch?.countDown()
            }
            override fun onError(error: Int) { sttLatch?.countDown() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        recognizer = rec
    }

    private fun listenForCommands(): List<String> {
        val rec = recognizer ?: return emptyList()
        val latch = CountDownLatch(1)
        sttResults = emptyList()
        sttLatch = latch

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)

        mainHandler.post { rec.startListening(intent) }
        val finished = latch.await(10, TimeUnit.SECONDS)
        if (!finished) mainHandler.post { rec.cancel() }
        return sttResults.filter { it.isNotBlank() }
    }

    // ---------- Text-to-speech ----------

    private fun wantTamil(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TAMIL, true)

    private fun initTts() {
        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                val engine = tts
                engine?.setSpeechRate(0.95f)
                engine?.setPitch(1.0f)
                engine?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsReady = true
                applyLanguageIfNeeded()
            }
        }
    }

    private fun applyLanguageIfNeeded() {
        if (!ttsReady) return
        val engine = tts ?: return
        val want = wantTamil()
        val code = if (want) 1 else 0
        if (code == appliedTamil) return
        if (want) {
            val r = engine.setLanguage(Locale("ta", "IN"))
            tamilActive = r == TextToSpeech.LANG_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            if (!tamilActive) engine.setLanguage(Locale("en", "IN"))
        } else {
            tamilActive = false
            engine.setLanguage(Locale("en", "IN"))
        }
        appliedTamil = code
    }

    private fun speakAndWait(text: String) {
        val engine = tts ?: return
        if (!ttsReady) return
        val latch = CountDownLatch(1)
        val id = "u" + System.nanoTime()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { latch.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { latch.countDown() }
        })
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        latch.await(20, TimeUnit.SECONDS)
    }

    private fun ensureVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (max * 0.8).toInt()
            if (cur < target) am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
        }
    }
}
