package com.example.minieditor

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private fun nowMs() = System.currentTimeMillis()

/** Where the project's code lives (CircleCI builds from there). The game zip is uploaded into it as game/game.zip. */
interface RepoHost {
    var cancelled: Boolean
    /** CircleCI project address prefix: "gh" or "bb". */
    val slugPrefix: String
    /** Uploads the zip and returns the branch it went to. */
    fun uploadGame(zip: ByteArray): String
}

class GitHubHost(token: String, repo: String, say: (String) -> Unit) : RepoHost {
    private val gh = CloudBuild(token, repo, say)
    override var cancelled: Boolean
        get() = gh.cancelled
        set(v) { gh.cancelled = v }
    override val slugPrefix = "gh"
    override fun uploadGame(zip: ByteArray): String = gh.uploadGame(zip).first
}

class BitbucketHost(
    private val consumerKey: String,
    private val consumerSecret: String,
    private val repo: String,
    private val say: (String) -> Unit
) : RepoHost {
    @Volatile override var cancelled = false
    override val slugPrefix = "bb"
    private val api = "https://api.bitbucket.org/2.0"
    private var bearer: String? = null

    /** Swaps the OAuth consumer key + secret for a 2-hour access token (client credentials). */
    private fun auth(): String {
        // an Atlassian email as the first box means: email + API token (plain Basic login)
        if (consumerKey.contains("@")) {
            return "Basic " + Base64.encodeToString((consumerKey + ":" + consumerSecret).toByteArray(), Base64.NO_WRAP)
        }
        var t = bearer
        if (t == null) {
            val basic = "Basic " + Base64.encodeToString((consumerKey + ":" + consumerSecret).toByteArray(), Base64.NO_WRAP)
            val r = Net.call("POST", "https://bitbucket.org/site/oauth2/access_token",
                mapOf("Authorization" to basic, "Content-Type" to "application/x-www-form-urlencoded"),
                "grant_type=client_credentials".toByteArray())
            if (r.code != 200) {
                throw RuntimeException("Bitbucket refused the OAuth key and secret (HTTP " + r.code + "). " +
                    "Check them, and that the consumer is a private consumer with Repositories: Read and Write.")
            }
            t = JSONObject(r.text).getString("access_token")
            bearer = t
        }
        return "Bearer " + t
    }

    override fun uploadGame(zip: ByteArray): String {
        say("Checking the Bitbucket repository...")
        val r = Net.call("GET", "$api/repositories/$repo", mapOf("Authorization" to auth(), "Accept" to "application/json"), null)
        if (r.code == 401 || r.code == 403) {
            throw RuntimeException("Bitbucket would not let this login into $repo (HTTP " + r.code + "). " +
                "Use an OAuth consumer with Repositories: Read and Write, or an API token with the read:repository and write:repository scopes.")
        }
        if (r.code != 200) throw RuntimeException("Cannot open $repo on Bitbucket (HTTP " + r.code + "). Use workspace/repo-name.")
        val branch = JSONObject(r.text).optJSONObject("mainbranch")?.optString("name", "main") ?: "main"
        if (cancelled) throw RuntimeException("Cancelled")

        say("Uploading the game (" + (zip.size / 1024) + " KB)...")
        val boundary = "----MiniEditor" + System.currentTimeMillis()
        val out = ByteArrayOutputStream()
        fun text(name: String, value: String) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").toByteArray())
        }
        text("message", "Game build from the app")
        text("branch", branch)
        // on Bitbucket the form field name is the path of the file in the repo
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"game/game.zip\"; filename=\"game.zip\"\r\n" +
            "Content-Type: application/zip\r\n\r\n").toByteArray())
        out.write(zip)
        out.write(("\r\n--" + boundary + "--\r\n").toByteArray())
        val up = Net.call("POST", "$api/repositories/$repo/src",
            mapOf("Authorization" to auth(), "Content-Type" to "multipart/form-data; boundary=" + boundary), out.toByteArray())
        if (up.code != 201 && up.code != 200) throw RuntimeException("Upload failed (HTTP " + up.code + "): " + Net.short(up.text))
        return branch
    }
}

/**
 * CircleCI: the game zip is pushed to the GitHub or Bitbucket repo (CircleCI builds from it), then a pipeline is started
 * through the CircleCI API (parameter build_game = true) and the finished game.apk is downloaded.
 */
class CircleBuild(
    private val host: RepoHost,
    repo: String,
    private val ciToken: String,
    private val say: (String) -> Unit
) : ApkBuilder {
    @Volatile private var flag = false
    override var cancelled: Boolean
        get() = flag
        set(v) { flag = v; host.cancelled = v }

    override var onProgress: ((Int) -> Unit)? = null
    private fun pct(p: Int) { onProgress?.invoke(p.coerceIn(0, 100)) }

    private val api = "https://circleci.com/api/v2"
    private val slug = host.slugPrefix + "/" + repo
    private fun hdr(json: Boolean = false): Map<String, String> =
        if (json) mapOf("Circle-Token" to ciToken, "Accept" to "application/json", "Content-Type" to "application/json")
        else mapOf("Circle-Token" to ciToken, "Accept" to "application/json")

    private fun check() { if (flag) throw RuntimeException("Cancelled") }

    private var fails = 0

    /** A network call that waits and tries again when the phone has no network for a while (for example while the app was in the background). */
    private fun net(method: String, url: String, headers: Map<String, String>, body: ByteArray? = null): Net.Reply {
        while (true) {
            check()
            try {
                val r = Net.call(method, url, headers, body)
                fails = 0
                return r
            } catch (e: java.io.IOException) {
                fails++
                say("No network, trying again... (" + fails + ")")
                if (fails > 60) throw RuntimeException("The network is gone: " + e.message)
                Thread.sleep(5000)
            }
        }
    }

    private fun netDownload(url: String, firstHeaders: Map<String, String>): Net.Reply {
        while (true) {
            check()
            try {
                val r = Net.download(url, firstHeaders)
                fails = 0
                return r
            } catch (e: java.io.IOException) {
                fails++
                say("No network, trying again... (" + fails + ")")
                if (fails > 60) throw RuntimeException("The network is gone: " + e.message)
                Thread.sleep(5000)
            }
        }
    }


    override fun build(zip: ByteArray): ByteArray {
        pct(3)
        val branch = host.uploadGame(zip)
        check()
        pct(15)
        say("Starting the CircleCI build...")
        val body = JSONObject().put("branch", branch).put("parameters", JSONObject().put("build_game", true)).toString().toByteArray()
        val t = net("POST", "$api/project/$slug/pipeline", hdr(true), body)
        if (t.code != 201 && t.code != 200) {
            throw RuntimeException("CircleCI would not start a build (HTTP " + t.code + "): " + Net.short(t.text) +
                "\nIs this repo added as a project in CircleCI, and is the CircleCI token right?")
        }
        val pipelineId = JSONObject(t.text).getString("id")
        pct(18)

        var wfId: String? = null
        val t0 = nowMs()
        while (true) {
            check()
            if (wfId == null) {
                val w = net("GET", "$api/pipeline/$pipelineId/workflow", hdr(), null)
                if (w.code == 200) {
                    val items = JSONObject(w.text).getJSONArray("items")
                    if (items.length() > 0) wfId = items.getJSONObject(0).getString("id")
                }
                if (wfId == null) {
                    pct(20)
                    say("Waiting for CircleCI to start the workflow...")
                    if (nowMs() - t0 > 4 * 60 * 1000L) {
                        throw RuntimeException("CircleCI made no workflow. Is .circleci/config.yml in the repo?")
                    }
                }
            } else {
                val w = net("GET", "$api/workflow/$wfId", hdr(), null)
                if (w.code == 200) {
                    val st = JSONObject(w.text).optString("status")
                    val secs = (nowMs() - t0) / 1000.0
                    // a build usually takes about 5 minutes: the bar creeps towards 90% and never stops moving
                    pct(25 + (65.0 * (1.0 - Math.exp(-secs / 200.0))).toInt())
                    say("CircleCI is building: " + st.replace('_', ' ') + " (" + secs.toInt() + " s)")
                    if (st == "success") break
                    if (st == "failed" || st == "error" || st == "failing" || st == "canceled" || st == "unauthorized" || st == "not_run") {
                        throw RuntimeException("The build ended with: " + st + ". Open the pipeline in CircleCI to see why.")
                    }
                }
            }
            if (nowMs() - t0 > 25 * 60 * 1000L) throw RuntimeException("The build took too long.")
            Thread.sleep(8000)
        }

        pct(92)
        say("Downloading the APK...")
        val j = net("GET", "$api/workflow/$wfId/job", hdr(), null)
        if (j.code != 200) throw RuntimeException("Could not read the CircleCI jobs (HTTP " + j.code + ").")
        val jobs = JSONObject(j.text).getJSONArray("items")
        var jobNumber = -1L
        for (i in 0 until jobs.length()) {
            val o = jobs.getJSONObject(i)
            if (o.optString("name") == "game" && o.has("job_number")) jobNumber = o.getLong("job_number")
        }
        if (jobNumber < 0) throw RuntimeException("CircleCI had no job called \"game\".")
        pct(94)
        val a = net("GET", "$api/project/$slug/$jobNumber/artifacts", hdr(), null)
        if (a.code != 200) throw RuntimeException("Could not list the build files (HTTP " + a.code + ").")
        val items = JSONObject(a.text).getJSONArray("items")
        var url: String? = null
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (o.optString("path").endsWith(".apk")) url = o.getString("url")
        }
        if (url == null) throw RuntimeException("The build finished but made no APK.")
        pct(96)
        val dl = netDownload(url, hdr())
        if (dl.code != 200) throw RuntimeException("Download failed (HTTP " + dl.code + ").")
        return dl.body
    }
}
