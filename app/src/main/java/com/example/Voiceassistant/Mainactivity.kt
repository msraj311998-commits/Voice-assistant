package com.example.voiceassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var listenBtn: Button
    private lateinit var voiceBtn: Button
    private lateinit var verifier: SpeakerVerifier

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var tamilAvailable = false
    @Volatile private var useTamil = true

    private var recognizer: SpeechRecognizer? = null
    private var tone: ToneGenerator? = null
    @Volatile private var sttResult: String? = null
    @Volatile private var sttLatch: CountDownLatch? = null

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var listening = false

    private val WAKE_PHRASE = "Hey MS"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verifier = SpeakerVerifier(this)
        buildUi()
        initTts()
        initRecognizer()
        tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        } catch (e: Exception) {
            null
        }
        ensureMicPermission()
        refreshState()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        val enrollBtn = Button(this).apply {
            text = "Enroll my voice"
            setOnClickListener { startEnrollment() }
        }
        listenBtn = Button(this).apply {
            text = "Start listening"
            setOnClickListener { toggleListening() }
        }
        val resetBtn = Button(this).apply {
            text = "Reset my voice"
            setOnClickListener { verifier.clearEnrollment(); refreshState() }
        }
        voiceBtn = Button(this).apply {
            text = "Voice: Tamil"
            setOnClickListener { toggleVoice() }
        }
        root.addView(status)
        root.addView(enrollBtn)
        root.addView(listenBtn)
        root.addView(resetBtn)
        root.addView(voiceBtn)
        setContentView(root)
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun refreshState() {
        when {
            !verifier.isEnrolled() ->
                setStatus("Munnadi 'Enroll my voice' click pannunga.")
            else -> setStatus("Ready. 'Start listening' click pannunga.")
        }
    }

    // ---------- Enrollment ----------

    private fun startEnrollment() {
        if (listening) stopListeningUi()
        worker.execute {
            val samples = ArrayList<ShortArray>()
            for (i in 1..3) {
                setStatus("Sample $i/3: \"$WAKE_PHRASE\" nu sollunga...")
                val a = AudioCapture.recordUtterance(maxSpeechMs = 2500, endSilenceMs = 400)
                if (a == null) { setStatus("Voice kekkala. Marupadi try pannunga."); return@execute }
                samples.add(a)
                Thread.sleep(500)
            }
            try {
                verifier.enroll(samples)
                setStatus("Voice enroll aachu. Ippo 'Start listening' click pannunga.")
            } catch (e: Exception) {
                setStatus("Enroll fail: ${e.message}")
            }
        }
    }

    // ---------- Listening loop ----------

    private fun toggleListening() {
        if (listening) {
            stopListeningUi()
            refreshState()
            return
        }
        if (!verifier.isEnrolled()) { refreshState(); return }
        listening = true
        listenBtn.text = "Stop listening"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ensureVolume()
        worker.execute { listenLoop() }
    }

    private fun stopListeningUi() {
        listening = false
        runOnUiThread {
            listenBtn.text = "Start listening"
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun listenLoop() {
        while (listening) {
            setStatus("Kekkuren... \"$WAKE_PHRASE\" nu sollunga")
            val audio = AudioCapture.recordUtterance(
                maxSpeechMs = 2500,
                endSilenceMs = 350,
                shouldStop = { !listening }
            ) ?: continue

            val score = verifier.score(audio)
            if (score < verifier.threshold) {
                setStatus("Ungal voice illa (score %.2f). Ignore pannitten.".format(score))
                continue
            }

            setStatus("Voice match %.2f. Sollunga...".format(score))
            beep()
            val command = listenForCommand()
            if (command == null) {
                setStatus("Naan onnum kekkala. Marupadi 'Hey MS' sollunga.")
                continue
            }

            val reply = Brain.reply(this, command)
            val spoken = if (useTamil && tamilAvailable) reply.tamil else reply.tanglish
            setStatus("Neenga: $command\nMS: $spoken")

            reply.action?.let { act -> runOnUiThread { act(this@MainActivity) } }
            if (reply.stopListening) stopListeningUi()
            speakAndWait(spoken)
        }
    }

    private fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        Thread.sleep(220)
    }

    // ---------- Speech-to-text (recognizer is created once and reused) ----------

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) {
                sttResult = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                sttLatch?.countDown()
            }
            override fun onError(error: Int) { sttLatch?.countDown() }
            override fun onReadyForSpeech(p: Bundle?) { setStatus("Sollunga, kekkuren...") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        recognizer = rec
    }

    private fun listenForCommand(): String? {
        val rec = recognizer ?: return null
        val latch = CountDownLatch(1)
        sttResult = null
        sttLatch = latch

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN") // English-ku "en-IN"
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        }
        runOnUiThread { rec.startListening(intent) }

        val finished = latch.await(12, TimeUnit.SECONDS)
        if (!finished) runOnUiThread { rec.cancel() }
        return sttResult
    }

    // ---------- Text-to-speech ----------

    private fun initTts() {
        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                val engine = tts
                engine?.setSpeechRate(1.0f)
                engine?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                applyVoiceLanguage()
                ttsReady = true
            }
        }
    }

    private fun applyVoiceLanguage() {
        val engine = tts ?: return
        if (useTamil) {
            val r = engine.setLanguage(Locale("ta", "IN"))
            tamilAvailable = r == TextToSpeech.LANG_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            if (!tamilAvailable) engine.setLanguage(Locale("en", "IN"))
        } else {
            engine.setLanguage(Locale("en", "IN"))
        }
        val tamilNow = useTamil && tamilAvailable
        runOnUiThread {
            voiceBtn.text = if (tamilNow) "Voice: Tamil (tap for English)" else "Voice: English (tap for Tamil)"
        }
    }

    private fun toggleVoice() {
        if (!ttsReady) return
        useTamil = !useTamil
        applyVoiceLanguage()
        if (useTamil && !tamilAvailable) {
            Toast.makeText(
                this,
                "Tamil voice phone-la illa. Settings-la Google Text-to-speech-la Tamil voice install pannunga.",
                Toast.LENGTH_LONG
            ).show()
        }
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

    // ---------- Permissions / lifecycle ----------

    private fun ensureMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Mic permission thevai", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        listening = false
        recognizer?.destroy()
        tone?.release()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }
}
