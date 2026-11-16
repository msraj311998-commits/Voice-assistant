package com.example.voiceassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var voiceBtn: Button
    private lateinit var verifier: SpeakerVerifier
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verifier = SpeakerVerifier(this)
        buildUi()
        ensurePermissions()
        updateVoiceButton()
    }

    override fun onResume() {
        super.onResume()
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
        val startBtn = Button(this).apply {
            text = "Start MS (bubble)"
            setOnClickListener { startAssistant() }
        }
        val stopBtn = Button(this).apply {
            text = "Stop MS"
            setOnClickListener { stopAssistant() }
        }
        val resetBtn = Button(this).apply {
            text = "Reset my voice"
            setOnClickListener { verifier.clearEnrollment(); refreshState() }
        }
        voiceBtn = Button(this).apply {
            setOnClickListener { toggleVoice() }
        }
        val help = TextView(this).apply {
            text = "Bubble: tap = pesalam | long-press = 'Hey MS' / 'MS' listening on-off"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        root.addView(status)
        root.addView(enrollBtn)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(resetBtn)
        root.addView(voiceBtn)
        root.addView(help)
        setContentView(root)
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun refreshState() {
        when {
            !verifier.isEnrolled() ->
                setStatus("Munnadi 'Enroll my voice' click pannunga.")
            AssistantService.running ->
                setStatus("MS odudhu. Bubble-a tap pannunga.")
            else ->
                setStatus("Ready. 'Start MS (bubble)' click pannunga.")
        }
    }

    // ---------- Voice language (Tamil / English) ----------

    private fun updateVoiceButton() {
        val p = getSharedPreferences(AssistantService.PREFS, Context.MODE_PRIVATE)
        val tamil = p.getBoolean(AssistantService.KEY_TAMIL, true)
        voiceBtn.text = if (tamil) "Voice: Tamil (tap for English)" else "Voice: English (tap for Tamil)"
    }

    private fun toggleVoice() {
        val p = getSharedPreferences(AssistantService.PREFS, Context.MODE_PRIVATE)
        val tamil = !p.getBoolean(AssistantService.KEY_TAMIL, true)
        p.edit().putBoolean(AssistantService.KEY_TAMIL, tamil).apply()
        updateVoiceButton()
    }

    // ---------- Start / stop ----------

    private fun startAssistant() {
        if (!verifier.isEnrolled()) { refreshState(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensurePermissions()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Next screen-la MS-ku 'Display over other apps' on pannunga, appuram back vandhu Start MS tap pannunga.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
            )
            return
        }
        startForegroundService(Intent(this, AssistantService::class.java))
        setStatus("MS odudhu. Bubble-a tap pannunga. Long-press = Hey MS on/off.")
        moveTaskToBack(true)
    }

    private fun stopAssistant() {
        stopService(Intent(this, AssistantService::class.java))
        setStatus("MS nindruduchu.")
    }

    // ---------- Enrollment: 3x "Hey MS" and 3x "MS" ----------

    private fun startEnrollment() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensurePermissions()
            return
        }
        stopService(Intent(this, AssistantService::class.java))
        worker.execute {
            Thread.sleep(500)
            val full = ArrayList<ShortArray>()
            val short = ArrayList<ShortArray>()

            for (i in 1..3) {
                setStatus("'Hey MS' nu sollunga ($i/3)")
                val a = AudioCapture.recordUtterance(maxSpeechMs = 2500, endSilenceMs = 400)
                if (a == null) { setStatus("Voice kekkala. Marupadi try pannunga."); return@execute }
                full.add(a)
                Thread.sleep(500)
            }
            for (i in 1..3) {
                setStatus("'MS' nu mattum sollunga ($i/3)")
                val a = AudioCapture.recordUtterance(maxSpeechMs = 2500, endSilenceMs = 400)
                if (a == null) { setStatus("Voice kekkala. Marupadi try pannunga."); return@execute }
                short.add(a)
                Thread.sleep(500)
            }
            try {
                verifier.enrollGroups(listOf(full, short))
                setStatus("Voice enroll aachu. Ippo 'Start MS (bubble)' click pannunga.")
            } catch (e: Exception) {
                setStatus("Enroll fail: ${e.message}")
            }
        }
    }

    // ---------- Permissions ----------

    private fun ensurePermissions() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Mic permission thevai", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
