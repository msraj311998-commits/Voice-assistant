package com.example.voiceassistant

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Model-free speaker verification (no .tflite needed).
 * Each recording is turned into an MFCC sequence. At enrollment we keep the owner's samples and
 * measure how different they are from each other (d0). Later, a new utterance is compared to the
 * samples with DTW. score = d0 / distance. About 1.0 means "as close as my own samples".
 * Works best for the same phrase, in a quiet room.
 */
class SpeakerVerifier(private val context: Context) {

    companion object {
        const val DEFAULT_THRESHOLD = 0.70f // higher = stricter
        private const val PREFS = "voice_prefs"
        private const val KEY_THRESHOLD = "threshold"
        private const val FILE_NAME = "voiceprint.bin"

        private const val SR = 16000
        private const val FRAME = 400
        private const val HOP = 160
        private const val NFFT = 512
        private const val NMEL = 26
        private const val NCEP = 13

        private fun hzToMel(f: Double): Double = 2595.0 * log10(1.0 + f / 700.0)
        private fun melToHz(m: Double): Double = 700.0 * (10.0.pow(m / 2595.0) - 1.0)

        private fun buildFilterbank(): Array<DoubleArray> {
            val lo = hzToMel(80.0)
            val hi = hzToMel(7600.0)
            val bins = IntArray(NMEL + 2) { i ->
                val hz = melToHz(lo + (hi - lo) * i / (NMEL + 1))
                floor((NFFT + 1) * hz / SR).toInt()
            }
            val fb = Array(NMEL) { DoubleArray(NFFT / 2 + 1) }
            for (m in 1..NMEL) {
                val l = bins[m - 1]
                val c = bins[m]
                val r = bins[m + 1]
                for (k in l until c) {
                    if (c > l && k < fb[m - 1].size) fb[m - 1][k] = (k - l).toDouble() / (c - l)
                }
                for (k in c until r) {
                    if (r > c && k < fb[m - 1].size) fb[m - 1][k] = (r - k).toDouble() / (r - c)
                }
            }
            return fb
        }

        private val FB: Array<DoubleArray> = buildFilterbank()
        private val DCT: Array<DoubleArray> = Array(NCEP) { n ->
            DoubleArray(NMEL) { m -> cos(PI * (n + 1) * (m + 0.5) / NMEL) }
        }
        private val WIN: DoubleArray = DoubleArray(FRAME) { i ->
            0.54 - 0.46 * cos(2.0 * PI * i / (FRAME - 1))
        }
    }

    private class VoicePrint(val d0: Double, val templates: List<Array<DoubleArray>>)

    private var cached: VoicePrint? = null

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val file get() = File(context.filesDir, FILE_NAME)

    /** No model file is needed any more. */
    fun modelAvailable(): Boolean = true

    fun isEnrolled(): Boolean = file.exists()

    var threshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        set(v) { prefs.edit().putFloat(KEY_THRESHOLD, v).apply() }

    /** Save the owner's voice from several samples of the same phrase. */
    fun enroll(samples: List<ShortArray>) {
        val t = samples.mapNotNull { features(it) }
        require(t.size >= 2) { "Voice romba kuraivu, marupadi pesunga" }

        var sum = 0.0
        var cnt = 0
        for (i in t.indices) {
            for (j in i + 1 until t.size) {
                sum += dtw(t[i], t[j])
                cnt++
            }
        }
        val d0 = max(sum / cnt, 1e-3)

        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeDouble(d0)
            out.writeInt(t.size)
            for (seq in t) {
                out.writeInt(seq.size)
                for (frame in seq) for (v in frame) out.writeDouble(v)
            }
        }
        cached = VoicePrint(d0, t)
    }

    fun clearEnrollment() {
        file.delete()
        cached = null
    }

    /** Around 1.0 or more = very close to the owner's voice. Near 0 = different voice. */
    fun score(audio: ShortArray): Float {
        val vp = load() ?: return -1f
        val f = features(audio) ?: return 0f
        var sum = 0.0
        for (t in vp.templates) sum += dtw(f, t)
        val d = max(sum / vp.templates.size, 1e-6)
        return min(vp.d0 / d, 2.0).toFloat()
    }

    fun isOwner(audio: ShortArray): Boolean = score(audio) >= threshold

    // ---------------- internals ----------------

    private fun load(): VoicePrint? {
        cached?.let { return it }
        if (!file.exists()) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
                val d0 = inp.readDouble()
                val count = inp.readInt()
                val list = ArrayList<Array<DoubleArray>>()
                for (k in 0 until count) {
                    val frames = inp.readInt()
                    list.add(Array(frames) { DoubleArray(NCEP) { inp.readDouble() } })
                }
                VoicePrint(d0, list)
            }.also { cached = it }
        } catch (e: Exception) {
            null
        }
    }

    /** Audio -> sequence of 13 MFCC values per 10 ms frame (silence at both ends trimmed). */
    private fun features(audio: ShortArray): Array<DoubleArray>? {
        if (audio.size < FRAME + 5 * HOP) return null

        var peak = 0.0
        for (s in audio) peak = max(peak, abs(s.toDouble()))
        if (peak <= 0.0) return null

        val x = DoubleArray(audio.size) { audio[it] / peak }
        val y = DoubleArray(x.size)
        y[0] = x[0]
        for (i in 1 until x.size) y[i] = x[i] - 0.97 * x[i - 1]

        val n = (x.size - FRAME) / HOP + 1
        if (n < 5) return null

        val energy = DoubleArray(n)
        var maxE = 0.0
        for (f in 0 until n) {
            var e = 0.0
            val o = f * HOP
            for (i in 0 until FRAME) e += x[o + i] * x[o + i]
            energy[f] = e
            if (e > maxE) maxE = e
        }
        val thr = maxE * 0.05
        var first = 0
        while (first < n && energy[first] < thr) first++
        var last = n - 1
        while (last > first && energy[last] < thr) last--
        if (last - first < 4) return null

        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val logMel = DoubleArray(NMEL)
        val out = ArrayList<DoubleArray>()

        for (f in first..last) {
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            val o = f * HOP
            for (i in 0 until FRAME) re[i] = y[o + i] * WIN[i]
            fft(re, im)

            for (m in 0 until NMEL) {
                var e = 0.0
                val w = FB[m]
                for (k in 0..NFFT / 2) {
                    if (w[k] != 0.0) e += w[k] * (re[k] * re[k] + im[k] * im[k])
                }
                logMel[m] = ln(max(e, 1e-10))
            }

            val cep = DoubleArray(NCEP)
            for (c in 0 until NCEP) {
                var s = 0.0
                for (m in 0 until NMEL) s += DCT[c][m] * logMel[m]
                cep[c] = s
            }
            out.add(cep)
        }
        return out.toTypedArray()
    }

    /** Dynamic time warping distance, averaged over the path length. */
    private fun dtw(a: Array<DoubleArray>, b: Array<DoubleArray>): Double {
        val n = a.size
        val m = b.size
        var prev = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
        prev[0] = 0.0
        for (i in 1..n) {
            val cur = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
            val ai = a[i - 1]
            for (j in 1..m) {
                val bj = b[j - 1]
                var d = 0.0
                for (k in 0 until NCEP) {
                    val diff = ai[k] - bj[k]
                    d += diff * diff
                }
                val best = min(prev[j], min(cur[j - 1], prev[j - 1]))
                cur[j] = sqrt(d) + best
            }
            prev = cur
        }
        return prev[m] / (n + m)
    }

    /** In-place radix-2 FFT. re and im must have a power-of-two length. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlr = cos(ang)
            val wli = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val xr = re[i + k + half]
                    val xi = im[i + k + half]
                    val vr = xr * wr - xi * wi
                    val vi = xr * wi + xi * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val nwr = wr * wlr - wi * wli
                    wi = wr * wli + wi * wlr
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
