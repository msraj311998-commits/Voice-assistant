package com.example.voiceassistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

object AudioCapture {
    const val SAMPLE_RATE = 16000
    private const val CHUNK = 320

    @SuppressLint("MissingPermission")
    fun recordUtterance(
        waitForSpeechMs: Int = 8000,
        maxSpeechMs: Int = 4000,
        endSilenceMs: Int = 600,
        rmsThreshold: Double = 900.0,
        shouldStop: () -> Boolean = { false }
    ): ShortArray? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK * 4)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) return null

        val out = ArrayList<Short>()
        val buf = ShortArray(CHUNK)
        var started = false
        var silentMs = 0
        var speechMs = 0
        var waitedMs = 0

        try {
            rec.startRecording()
            while (!shouldStop()) {
                val n = rec.read(buf, 0, CHUNK)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                val chunkMs = n * 1000 / SAMPLE_RATE

                if (!started) {
                    if (rms > rmsThreshold) {
                        started = true
                        for (i in 0 until n) out.add(buf[i])
                        speechMs += chunkMs
                    } else {
                        waitedMs += chunkMs
                        if (waitedMs >= waitForSpeechMs) return null
                    }
                } else {
                    for (i in 0 until n) out.add(buf[i])
                    speechMs += chunkMs
                    if (rms > rmsThreshold) silentMs = 0 else silentMs += chunkMs
                    if (silentMs >= endSilenceMs || speechMs >= maxSpeechMs) break
                }
            }
        } finally {
            rec.stop()
            rec.release()
        }
        return if (started) out.toShortArray() else null
    }
}
