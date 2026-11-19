package app.speaker.dictate

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal blocking HTTP helpers (no third-party libraries). Never call on the main thread. */
object Http {
    private const val TAG = "Speaker.http"

    private fun open(url: String, headers: Map<String, String>, readTimeoutMs: Int = 120_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    fun postJson(url: String, headers: Map<String, String>, body: JSONObject, readTimeoutMs: Int = 120_000): JSONObject {
        val bytes = body.toString().toByteArray()
        val conn = open(url, headers, readTimeoutMs)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        return read(conn)
    }

    fun getJson(url: String, headers: Map<String, String>): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return read(conn)
    }

    fun postFile(url: String, headers: Map<String, String>, contentType: String, file: File): JSONObject {
        val conn = open(url, headers)
        conn.setRequestProperty("Content-Type", contentType)
        conn.setFixedLengthStreamingMode(file.length())
        conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        return read(conn)
    }

    fun postMultipart(
        url: String, headers: Map<String, String>, fields: Map<String, String>,
        fileField: String, file: File, mime: String,
    ): JSONObject {
        val boundary = "----SpeakerBoundary${System.nanoTime()}"
        val head = StringBuilder()
        fields.forEach { (k, v) ->
            head.append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        head.append("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"${file.name}\"\r\n")
        head.append("Content-Type: $mime\r\n\r\n")
        val pre = head.toString().toByteArray()
        val post = "\r\n--$boundary--\r\n".toByteArray()

        val conn = open(url, headers)
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setFixedLengthStreamingMode(pre.size + file.length() + post.size)
        conn.outputStream.use { out ->
            out.write(pre)
            file.inputStream().use { it.copyTo(out) }
            out.write(post)
        }
        return read(conn)
    }

    private fun read(conn: HttpURLConnection): JSONObject {
        val started = System.currentTimeMillis()
        // Log host + path only: query strings could carry secrets for some providers.
        val where = "${conn.url.host}${conn.url.path}"
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val ms = System.currentTimeMillis() - started
            if (code !in 200..299) {
                Log.w(TAG, "${conn.requestMethod} $where -> HTTP $code in ${ms}ms, body: ${body.take(1000)}")
                throw IOException("HTTP $code: ${errorMessage(body)}")
            }
            Log.i(TAG, "${conn.requestMethod} $where -> HTTP $code in ${ms}ms (${body.length} bytes)")
            return JSONObject(body)
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP ") != true) Log.w(TAG, "${conn.requestMethod} $where failed: $e")
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(body: String): String = runCatching {
        val json = JSONObject(body)
        when (val err = json.opt("error")) {
            is JSONObject -> err.optString("message", err.toString())
            is String -> err
            else -> json.optString("err_msg", body)
        }
    }.getOrDefault(body).take(300)
}
