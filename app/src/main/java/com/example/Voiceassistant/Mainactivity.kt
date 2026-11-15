package com.example.voiceassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
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
    private lateinit var enrollBtn: Button
    private lateinit var listenBtn: Button
    private lateinit var verifier: SpeakerVerifier
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var listening = false

    private val WAKE_PHRASE = "Hey MS"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verifier = SpeakerVerifier(this)
        buildUi()
        initTts()
        ensureMicPermission()
        refreshState()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        enrollBtn = Button(this).apply {
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
        root.addView(status)
        root.addView(enrollBtn)
        root.addView(listenBtn)
        root.addView(resetBtn)
        setContentView(root)
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun refreshState() {
        when {
            !verifier.modelAvailable() ->
                setStatus("speaker_model.tflite assets-la illa.\nREADME paarunga.")
            !verifier.isEnrolled() ->
                setStatus("Munnadi 'Enroll my voice' click pannunga.")
            else -> setStatus("Ready. 'Start listening' click pannunga.")
        }
    }

    private fun startEnrollment() {
        if (!verifier.modelAvailable()) { refreshState(); return }
        if (listening) toggleListening()
        worker.execute {
            val samples = ArrayList<ShortArray>()
            for (i in 1..3) {
                setStatus("Sample $i/3: \"$WAKE_PHRASE\" nu sollunga...")
                val a = AudioCapture.recordUtterance()
                if (a == null) { setStatus("Voice kekkala. Marupadi try pannunga."); return@execute }
                samples.add(a)
                Thread.sleep(800)
            }
            try {
                verifier.enroll(samples)
                setStatus("Voice enroll aachu. Ippo 'Start listening' click pannunga.")
            } catch (e: Exception) {
                setStatus("Enroll fail: ${e.message}")
            }
        }
    }

    private fun toggleListening() {
        if (listening) {
            listening = false
            listenBtn.text = "Start listening"
            refreshState()
            return
        }
        if (!verifier.modelAvailable() || !verifier.isEnrolled()) { refreshState(); return }
        listening = true
        listenBtn.text = "Stop listening"
        worker.execute { listenLoop() }
    }

    private fun listenLoop() {
        while (listening) {
            setStatus("Kekkuren... \"$WAKE_PHRASE\" nu sollunga")
            val audio = AudioCapture.recordUtterance(shouldStop = { !listening }) ?: continue
            val score = verifier.score(audio)
            if (score < verifier.threshold) {
                setStatus("Ungal voice illa (score %.2f). Ignore pannitten.".format(score))
                continue
            }
            speakAndWait("Sollunga")
            val command = listenForCommand()
            if (command == null) {
                speakAndWait("Naan onnum kekkala")
                continue
            }
            val reply = Brain.reply(command)
            setStatus("Neenga: $command\nMS: $reply")
            speakAndWait(reply)
        }
    }

    private fun listenForCommand(): String? {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
        val latch = CountDownLatch(1)
        var result: String? = null

        runOnUiThread {
            val rec = SpeechRecognizer.createSpeechRecognizer(this)
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    result = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    rec.destroy(); latch.countDown()
                }
                override fun onError(error: Int) { rec.destroy(); latch.countDown() }
                override fun onReadyForSpeech(p: Bundle?) { setStatus("Sollunga, kekkuren...") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            rec.startListening(intent)
        }
        latch.await(15, TimeUnit.SECONDS)
        return result
    }

    private fun initTts() {
        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                val ta = tts?.setLanguage(Locale("ta", "IN"))
                if (ta == TextToSpeech.LANG_MISSING_DATA || ta == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("en", "IN"))
                }
                ttsReady = true
            }
        }
    }

    private fun speakAndWait(text: String) {
        if (!ttsReady) return
        val latch = CountDownLatch(1)
        val id = "u" + System.nanoTime()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { latch.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { latch.countDown() }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        latch.await(20, TimeUnit.SECONDS)
    }

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
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }
}
