package com.example.minieditor

import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Small synthesized sound effects (WAV) for the sample games, so they have sound without any file to import.
 * Names: shot, splash, swim, hurt, reload, pickup, hit, bubble, gasp.
 */
object SoundGen {
    private const val RATE = 22050

    private class Rng(var s: Long = 12345L) {
        fun next(): Float {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 33).toInt() and 0xFFFF) / 32768f - 1f
        }
    }

    private fun wav(len: Float, gain: Float = 0.85f, f: (Float, Int) -> Float): ByteArray {
        val n = (len * RATE).toInt()
        val raw = FloatArray(n) { i -> f(i / RATE.toFloat(), i) }
        var peak = 0.0001f
        for (v in raw) peak = max(peak, kotlin.math.abs(v))
        val pcm = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (raw[i] / peak * gain * 32767f).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val out = ByteArray(44 + pcm.size)
        fun put32(o: Int, v: Int) { for (k in 0..3) out[o + k] = ((v shr (8 * k)) and 0xFF).toByte() }
        fun put16(o: Int, v: Int) { out[o] = (v and 0xFF).toByte(); out[o + 1] = ((v shr 8) and 0xFF).toByte() }
        "RIFF".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        put32(4, 36 + pcm.size)
        "WAVEfmt ".forEachIndexed { i, c -> out[8 + i] = c.code.toByte() }
        put32(16, 16); put16(20, 1); put16(22, 1); put32(24, RATE); put32(28, RATE * 2); put16(32, 2); put16(34, 16)
        "data".forEachIndexed { i, c -> out[36 + i] = c.code.toByte() }
        put32(40, pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }

    private fun tone(freq: Float, t: Float) = sin(2f * PI.toFloat() * freq * t)

    private fun shot(): ByteArray {
        val r = Rng(1)
        var lp = 0f
        return wav(0.28f) { t, _ ->
            lp += (r.next() - lp) * 0.55f
            lp * exp(-t * 26f) * 1.0f + tone(95f, t) * exp(-t * 16f) * 0.8f + tone(190f, t) * exp(-t * 30f) * 0.3f
        }
    }

    private fun splash(): ByteArray {
        val r = Rng(2)
        var lp = 0f
        return wav(0.7f) { t, _ ->
            val cut = 0.5f * exp(-t * 3.5f) + 0.08f
            lp += (r.next() - lp) * cut
            val env = min(1f, t / 0.015f) * exp(-t * 5.5f)
            var bloop = 0f
            for (st in floatArrayOf(0.05f, 0.13f, 0.22f, 0.33f)) {
                val u = t - st
                if (u > 0f) bloop += tone(380f + 2200f * u, u) * exp(-u * 22f) * 0.25f
            }
            lp * env * 1.6f + bloop
        }
    }

    private fun swim(): ByteArray {
        val r = Rng(3)
        var lp = 0f
        var lp2 = 0f
        return wav(0.5f, 0.6f) { t, _ ->
            lp += (r.next() - lp) * 0.18f
            lp2 += (lp - lp2) * 0.3f
            val env = sin(PI.toFloat() * t / 0.5f)
            lp2 * env * env * 2.2f + tone(260f + 300f * t, t) * exp(-t * 9f) * 0.12f
        }
    }

    /** Resonant filter: lets frequencies around [freq] through (used to turn a buzz into a vowel-like grunt). */
    private class Formant(freq: Float, bw: Float) {
        private val r = exp(-PI.toFloat() * bw / RATE)
        private val a1 = 2f * r * kotlin.math.cos(2f * PI.toFloat() * freq / RATE)
        private val a2 = -r * r
        private val g = 1f - r
        private var y1 = 0f
        private var y2 = 0f
        fun run(x: Float): Float {
            val y = g * x + a1 * y1 + a2 * y2
            y2 = y1
            y1 = y
            return y
        }
    }

    /** Getting shot: a dull body thump, a flesh slap, and a short male "ugh" grunt. */
    private fun hurt(): ByteArray {
        val r = Rng(4)
        var slapLp = 0f
        var phase = 0f
        val f1 = Formant(620f, 140f)
        val f2 = Formant(1080f, 190f)
        val f3 = Formant(2500f, 300f)
        return wav(0.42f, 0.9f) { t, _ ->
            // thump: low body hit, pitch dropping
            val thump = tone(78f - 40f * t, t) * exp(-t * 17f) * 1.0f
            // slap: short dull noise burst
            slapLp += (r.next() - slapLp) * 0.28f
            val slap = slapLp * exp(-t * 55f) * 0.9f
            // grunt: buzzing voice (pitch falls 125 -> 85 Hz) shaped by vowel resonances
            val u = t - 0.025f
            var grunt = 0f
            if (u > 0f) {
                val f0 = 125f - 160f * u
                phase += max(f0, 70f) / RATE
                val saw = (phase % 1f) * 2f - 1f
                val src = saw * 0.8f + r.next() * 0.2f
                val v = f1.run(src) * 1.0f + f2.run(src) * 0.7f + f3.run(src) * 0.25f
                grunt = v * 6.5f * min(1f, u / 0.012f) * exp(-u * 8f) * (0.6f + 0.4f * exp(-u * 14f))
            }
            thump + slap + grunt * 0.55f
        }
    }

    private fun reload(): ByteArray {
        val r = Rng(5)
        return wav(0.55f) { t, _ ->
            var v = 0f
            for (st in floatArrayOf(0f, 0.3f)) {
                val u = t - st
                if (u > 0f) v += (r.next() * 0.6f + tone(1100f, u) * 0.6f + tone(420f, u) * 0.5f) * exp(-u * 45f)
            }
            v
        }
    }

    private fun pickup(): ByteArray = wav(0.3f, 0.7f) { t, _ ->
        val f = if (t < 0.1f) 660f else 990f
        tone(f, t) * exp(-t * 9f) * min(1f, t / 0.005f)
    }

    /** You hit an enemy: a short crisp tick with a dull thud under it. */
    private fun hit(): ByteArray {
        val r = Rng(6)
        var hp = 0f
        return wav(0.16f, 0.8f) { t, _ ->
            val n = r.next()
            hp = n - hp * 0.2f   // brighter noise
            hp * exp(-t * 70f) * 0.7f + tone(1700f, t) * exp(-t * 90f) * 0.4f + tone(160f, t) * exp(-t * 32f) * 0.8f
        }
    }

    /** A few bubbles blooping upwards. */
    private fun bubbleSnd(): ByteArray {
        return wav(0.55f, 0.7f) { t, _ ->
            var v = 0f
            for (k in 0..2) {
                val u = t - k * 0.16f
                if (u > 0f) v += tone(420f + 700f * k + 3000f * u, u) * exp(-u * 24f) * 0.6f
            }
            v
        }
    }

    /** Coming up for air: a quick breath in. */
    private fun gasp(): ByteArray {
        val r = Rng(7)
        val f1 = Formant(700f, 260f)
        val f2 = Formant(1700f, 400f)
        return wav(0.55f, 0.8f) { t, _ ->
            val n = r.next()
            val env = min(1f, t / 0.08f) * exp(-t * 4.5f)
            (f1.run(n) * 1.0f + f2.run(n) * 0.5f) * env * 9f
        }
    }

    /** Writes the effects into [dir] (replacing files with the same names). */
    fun install(dir: File) {
        dir.mkdirs()
        val all = mapOf(
            "shot.wav" to shot(), "splash.wav" to splash(), "swim.wav" to swim(), "hurt.wav" to hurt(),
            "reload.wav" to reload(), "pickup.wav" to pickup(), "hit.wav" to hit(),
            "bubble.wav" to bubbleSnd(), "gasp.wav" to gasp()
        )
        for ((name, bytes) in all) {
            try { File(dir, name).writeBytes(bytes) } catch (e: Throwable) { }
        }
    }
}
