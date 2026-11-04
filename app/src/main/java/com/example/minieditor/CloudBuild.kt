package com.example.minieditor

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Builds the game into an APK with GitHub Actions: uploads game/game.zip to the repo, waits for the
 * "Build Game APK" workflow, and downloads the finished app-debug.apk. Plain HttpURLConnection, no libraries.
 */
class CloudBuild(private val token: String, private val repo: String, private val say: (String) -> Unit) : ApkBuilder {
    @Volatile override var cancelled = false
    private val api = "https://api.github.com"

    private class Reply(val code: Int, val body: ByteArray, val location: String?) {
        val text: String get() = String(body)
    }

    private fun http(method: String, url: String, body: ByteArray? = null, auth: Boolean = true): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = false
        c.connectTimeout = 20000
        c.readTimeout = 180000
        c.setRequestProperty("User-Agent", "MiniEditor")
        if (auth) {
            c.setRequestProperty("Authorization", "Bearer " + token)
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val data = stream?.use { it.readBytes() } ?: ByteArray(0)
        val loc = c.getHeaderField("Location")
        c.disconnect()
        return Reply(code, data, loc)
    }

    private fun short(s: String): String = if (s.length > 200) s.substring(0, 200) + "..." else s

    private fun check() {
        if (cancelled) throw RuntimeException("Cancelled")
    }

    private fun findRun(commit: String): JSONObject? {
        val r = http("GET", "$api/repos/$repo/actions/runs?head_sha=$commit&per_page=30")
        if (r.code != 200) return null
        val runs = JSONObject(r.text).getJSONArray("workflow_runs")
        for (i in 0 until runs.length()) {
            val o = runs.getJSONObject(i)
            if (o.optString("name") == "Build Game APK") return o
        }
        return null
    }

    private fun latestRun(): JSONObject? {
        val r = http("GET", "$api/repos/$repo/actions/workflows/game.yml/runs?per_page=1")
        if (r.code != 200) return null
        val runs = JSONObject(r.text).getJSONArray("workflow_runs")
        return if (runs.length() > 0) runs.getJSONObject(0) else null
    }

    /** Uploads [zip] as game/game.zip on the default branch. Returns (branch, commit sha). */
    fun uploadGame(zip: ByteArray): Pair<String, String> {
        say("Checking the repository...")
        val r = http("GET", "$api/repos/$repo")
        if (r.code == 401) throw RuntimeException("The token was refused (401). Make a new token.")
        if (r.code != 200) throw RuntimeException("Cannot open $repo (HTTP " + r.code + "). Check the repo name and that the token can use it.")
        val branch = JSONObject(r.text).getString("default_branch")
        check()

        say("Uploading the game (" + (zip.size / 1024) + " KB)...")
        var sha: String? = null
        val ex = http("GET", "$api/repos/$repo/contents/game/game.zip?ref=$branch")
        if (ex.code == 200) sha = JSONObject(ex.text).optString("sha", "").ifEmpty { null }
        val put = JSONObject()
            .put("message", "Game build from the app")
            .put("content", Base64.encodeToString(zip, Base64.NO_WRAP))
            .put("branch", branch)
        if (sha != null) put.put("sha", sha)
        val up = http("PUT", "$api/repos/$repo/contents/game/game.zip", put.toString().toByteArray())
        if (up.code != 200 && up.code != 201) {
            throw RuntimeException("Upload failed (HTTP " + up.code + "): " + short(up.text) + "\n(The token needs Contents: read and write.)")
        }
        val commit = JSONObject(up.text).getJSONObject("commit").getString("sha")
        check()
        return Pair(branch, commit)
    }

    /** Uploads [zip] as game/game.zip, waits for the build and returns the APK bytes. */
    override fun build(zip: ByteArray): ByteArray {
        val (branch, commit) = uploadGame(zip)

        // wait for the workflow run (started by the push; started by hand if the push did not start one)
        say("Waiting for GitHub to start the build...")
        val t0 = System.currentTimeMillis()
        var dispatched = false
        var run: JSONObject? = null
        while (run == null) {
            check()
            run = findRun(commit)
            if (run == null && dispatched) run = latestRun()
            if (run == null) {
                val waited = System.currentTimeMillis() - t0
                if (!dispatched && waited > 45000) {
                    say("Starting the build by hand...")
                    val d = http("POST", "$api/repos/$repo/actions/workflows/game.yml/dispatches",
                        JSONObject().put("ref", branch).toString().toByteArray())
                    if (d.code != 204) {
                        throw RuntimeException("No build started and it could not be started (HTTP " + d.code + "). " +
                            "Is .github/workflows/game.yml pushed to the repo? (The token also needs Actions: read and write.)")
                    }
                    dispatched = true
                    Thread.sleep(6000)
                } else if (waited > 240000) {
                    throw RuntimeException("The build never started. Check the Actions tab on GitHub.")
                } else {
                    Thread.sleep(5000)
                }
            }
        }
        val found = run!!
        val runId = found.getLong("id")
        val htmlUrl = found.optString("html_url")

        // wait until it finishes
        val t1 = System.currentTimeMillis()
        while (true) {
            check()
            val rr = http("GET", "$api/repos/$repo/actions/runs/$runId")
            if (rr.code == 200) {
                val o = JSONObject(rr.text)
                val status = o.optString("status")
                say("GitHub is building: " + status.replace('_', ' ') + " (" + ((System.currentTimeMillis() - t1) / 1000) + " s)")
                if (status == "completed") {
                    val concl = o.optString("conclusion")
                    if (concl != "success") throw RuntimeException("The build failed (" + concl + "). Open $htmlUrl to see why.")
                    break
                }
            }
            if (System.currentTimeMillis() - t1 > 25 * 60 * 1000L) throw RuntimeException("The build took too long. Check $htmlUrl")
            Thread.sleep(8000)
        }

        say("Downloading the APK...")
        val ar = http("GET", "$api/repos/$repo/actions/runs/$runId/artifacts")
        if (ar.code != 200) throw RuntimeException("Could not list the build files (HTTP " + ar.code + ").")
        val arts = JSONObject(ar.text).getJSONArray("artifacts")
        var url: String? = null
        for (i in 0 until arts.length()) {
            val a = arts.getJSONObject(i)
            if (a.optString("name") == "game-apk") url = a.getString("archive_download_url")
        }
        if (url == null) throw RuntimeException("The build finished but made no APK. Open $htmlUrl")
        var dl = http("GET", url)
        if ((dl.code == 301 || dl.code == 302 || dl.code == 303 || dl.code == 307) && dl.location != null) {
            dl = http("GET", dl.location!!, auth = false)
        }
        if (dl.code != 200) throw RuntimeException("Download failed (HTTP " + dl.code + ").")
        ZipInputStream(ByteArrayInputStream(dl.body)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith(".apk")) return z.readBytes()
            }
        }
        throw RuntimeException("The download had no APK inside.")
    }
}
