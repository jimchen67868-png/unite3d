package com.example.minieditor

import java.net.HttpURLConnection
import java.net.URL

/** Something that can turn a game zip into an APK somewhere in the cloud. */
interface ApkBuilder {
    var cancelled: Boolean
    /** Called with 0-100 as the build moves along (for the progress bar). */
    var onProgress: ((Int) -> Unit)?
        get() = null
        set(_) {}
    fun build(zip: ByteArray): ByteArray
}

object Net {
    class Reply(val code: Int, val body: ByteArray, val location: String?) {
        val text: String get() = String(body)
    }

    /** One plain HTTP call. Redirects are not followed (the caller decides whether to send the headers on). */
    fun call(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = false
        c.connectTimeout = 20000
        c.readTimeout = 180000
        c.setRequestProperty("User-Agent", "MiniEditor")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
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

    /** GET that follows up to 3 redirects; the headers are only sent to the first address. */
    fun download(url: String, firstHeaders: Map<String, String>): Reply {
        var r = call("GET", url, firstHeaders, null)
        var n = 0
        while ((r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307 || r.code == 308) && r.location != null && n < 3) {
            r = call("GET", r.location!!, emptyMap(), null)
            n++
        }
        return r
    }

    fun short(s: String): String = if (s.length > 200) s.substring(0, 200) + "..." else s
}
