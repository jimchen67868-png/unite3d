package com.example.minieditor

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File

/**
 * Sound effects and music for scripts. Sound files live in one folder; a script refers to a file by its name
 * ("jump.wav") or just the part before the dot ("jump"). Short files are played through a SoundPool (instant,
 * many at once); long files and music go through MediaPlayer.
 */
class Audio(private val soundsDir: () -> File) {
    private var pool: SoundPool? = null
    private val poolIds = HashMap<String, Int>()
    private val ready = HashSet<Int>()
    private var music: MediaPlayer? = null
    private var musicName = ""
    private var musicVol = 1f
    private var musicOn = false
    private var paused = false
    private val shots = ArrayList<MediaPlayer>()
    private val warned = HashSet<String>()
    var warn: (String) -> Unit = {}

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
        .build()

    /** Loads the short sounds so they play with no delay. */
    fun start() {
        try {
            val p = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
            p.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
            pool = p
            for (f in soundsDir().listFiles() ?: emptyArray()) {
                if (f.isFile && f.length() <= 400_000L) {
                    try { poolIds[f.name] = p.load(f.path, 1) } catch (e: Throwable) { }
                }
            }
        } catch (e: Throwable) {
            pool = null
        }
    }

    private fun find(name: String): File? {
        val n = name.trim()
        if (n.isEmpty() || n.contains('/') || n.contains('\\')) return null
        val files = soundsDir().listFiles()?.filter { it.isFile } ?: return null
        return files.firstOrNull { it.name == n }
            ?: files.firstOrNull { it.name.equals(n, true) }
            ?: files.firstOrNull { it.name.substringBeforeLast('.').equals(n, true) }
    }

    private fun missing(name: String): Boolean {
        if (warned.add(name)) warn("sound not found: \"" + name + "\" (File > Sounds and music...)")
        return false
    }

    /** One short effect. [volume] 0..1, [rate] 0.5..2 (pitch and speed). */
    fun playSound(name: String, volume: Float, rate: Float): Boolean {
        val f = find(name) ?: return missing(name)
        val v = volume.coerceIn(0f, 1f)
        val id = poolIds[f.name]
        val p = pool
        if (p != null && id != null && ready.contains(id)) {
            p.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f))
            return true
        }
        // not loaded in the pool (long file, or still loading): play it as a one-shot player
        if (shots.size >= 6) return false
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(f.path)
            mp.setVolume(v, v)
            mp.setOnPreparedListener { if (!paused) it.start() }
            mp.setOnCompletionListener { m -> shots.remove(m); try { m.release() } catch (e: Throwable) { } }
            mp.setOnErrorListener { m, _, _ -> shots.remove(m); try { m.release() } catch (e: Throwable) { }; true }
            shots.add(mp)
            mp.prepareAsync()
            return true
        } catch (e: Throwable) {
            return false
        }
    }

    /** Background music; starting the same song again just changes its volume. */
    fun playMusic(name: String, volume: Float, loop: Boolean): Boolean {
        val f = find(name) ?: return missing(name)
        musicVol = volume.coerceIn(0f, 1f)
        val cur = music
        if (cur != null && musicName == f.name) {
            try { cur.isLooping = loop; cur.setVolume(musicVol, musicVol) } catch (e: Throwable) { }
            return true
        }
        stopMusic()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(f.path)
            mp.isLooping = loop
            mp.setVolume(musicVol, musicVol)
            mp.setOnPreparedListener { musicOn = true; if (!paused) it.start() }
            mp.setOnErrorListener { m, _, _ -> if (music === m) { music = null; musicOn = false }; try { m.release() } catch (e: Throwable) { }; true }
            music = mp
            musicName = f.name
            mp.prepareAsync()
            return true
        } catch (e: Throwable) {
            music = null
            return false
        }
    }

    fun stopMusic() {
        val m = music ?: return
        music = null
        musicOn = false
        musicName = ""
        try { m.stop() } catch (e: Throwable) { }
        try { m.release() } catch (e: Throwable) { }
    }

    fun setMusicVolume(v: Float) {
        musicVol = v.coerceIn(0f, 1f)
        try { music?.setVolume(musicVol, musicVol) } catch (e: Throwable) { }
    }

    fun pause() {
        paused = true
        try { if (musicOn) music?.pause() } catch (e: Throwable) { }
        for (m in shots.toList()) try { m.pause() } catch (e: Throwable) { }
        try { pool?.autoPause() } catch (e: Throwable) { }
    }

    fun resume() {
        paused = false
        try { if (musicOn) music?.start() } catch (e: Throwable) { }
        for (m in shots.toList()) try { m.start() } catch (e: Throwable) { }
        try { pool?.autoResume() } catch (e: Throwable) { }
    }

    /** Stops everything and frees the players. */
    fun release() {
        stopMusic()
        for (m in shots.toList()) { try { m.release() } catch (e: Throwable) { } }
        shots.clear()
        try { pool?.release() } catch (e: Throwable) { }
        pool = null
        poolIds.clear()
        ready.clear()
    }
}
