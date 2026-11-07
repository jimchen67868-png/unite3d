package com.example.minieditor

import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Small synthesized sound effects (WAV) for the sample games, so they have sound without any file to import.
 * Names: shot, splash, swim, hurt, reload, pickup, hit.
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

    private fun hurt(): ByteArray {
        val r = Rng(4)
        return wav(0.28f) { t, _ ->
            val f = 330f - 520f * t
            val saw = ((f * t) % 1f) * 2f - 1f
            (saw * 0.6f + r.next() * 0.25f) * exp(-t * 11f)
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

    private fun hit(): ByteArray {
        val r = Rng(6)
        return wav(0.14f) { t, _ -> (r.next() * 0.7 + tone(230f, t) * 0.6f).toFloat() * exp(-t * 38f) }
    }

    /** Writes the effects into [dir] (replacing files with the same names). */
    fun install(dir: File) {
        dir.mkdirs()
        val all = mapOf(
            "shot.wav" to shot(), "splash.wav" to splash(), "swim.wav" to swim(), "hurt.wav" to hurt(),
            "reload.wav" to reload(), "pickup.wav" to pickup(), "hit.wav" to hit()
        )
        for ((name, bytes) in all) {
            try { File(dir, name).writeBytes(bytes) } catch (e: Throwable) { }
        }
    }
}
