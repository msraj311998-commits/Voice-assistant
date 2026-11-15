package com.example.voiceassistant

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class SpeakerVerifier(private val context: Context) {

    companion object {
        const val MODEL_FILE = "speaker_model.tflite"
        const val MODEL_SAMPLES = 48000
        const val DEFAULT_THRESHOLD = 0.70f
        private const val PREFS = "voice_prefs"
        private const val KEY_EMBEDDING = "owner_embedding"
        private const val KEY_THRESHOLD = "threshold"
    }

    private val interpreter: Interpreter by lazy {
        val fd = context.assets.openFd(MODEL_FILE)
        val map = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        Interpreter(map)
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelAvailable(): Boolean = try {
        context.assets.openFd(MODEL_FILE).close(); true
    } catch (e: Exception) { false }

    fun isEnrolled(): Boolean = prefs.contains(KEY_EMBEDDING)

    var threshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(v) { prefs.edit().putFloat(KEY_THRESHOLD, v).apply() }

    fun enroll(samples: List<ShortArray>) {
        val embs = samples.map { embed(it) }
        val dim = embs[0].size
        val avg = FloatArray(dim)
        for (e in embs) for (i in 0 until dim) avg[i] += e[i] / embs.size
        prefs.edit().putString(KEY_EMBEDDING, avg.joinToString(",")).apply()
    }

    fun clearEnrollment() = prefs.edit().remove(KEY_EMBEDDING).apply()

    fun score(audio: ShortArray): Float {
        val ref = prefs.getString(KEY_EMBEDDING, null)
            ?.split(",")?.map { it.toFloat() }?.toFloatArray() ?: return -1f
        return cosine(ref, embed(audio))
    }

    fun isOwner(audio: ShortArray): Boolean = score(audio) >= threshold

    private fun prepareInput(audio: ShortArray): Array<FloatArray> {
        val x = FloatArray(MODEL_SAMPLES)
        val n = minOf(audio.size, MODEL_SAMPLES)
        for (i in 0 until n) x[i] = audio[i] / 32768f
        var peak = 0f
        for (v in x) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        if (peak > 0f) for (i in x.indices) x[i] /= peak
        return arrayOf(x)
    }

    private fun embed(audio: ShortArray): FloatArray {
        val input = prepareInput(audio)
        val dim = interpreter.getOutputTensor(0).shape().last()
        val output = Array(1) { FloatArray(dim) }
        interpreter.run(input, output)
        return output[0]
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }
}
