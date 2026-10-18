// OpenClip M10 — self-hosted WebDAV sync (app-only, no engine symbols).
// Basic auth over the URL the user provides; the client never bypasses
// TLS itself and never contacts anything the user did not configure
// (the "sans telemetry" goal). Failures return false, never throw.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.media

import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object WebdavClient {

    fun push(docFile: File, url: String, user: String, pass: String): Boolean =
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty(
                    "Authorization", "Basic " + basicCred(user, pass)
                )
                setRequestProperty("Content-Type", "application/octet-stream")
            }
            docFile.inputStream().use { i ->
                c.outputStream.use { o -> i.copyTo(o) }
            }
            val code = c.responseCode
            c.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }

    fun pull(url: String, user: String, pass: String, out: File): Boolean {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty(
                    "Authorization", "Basic " + basicCred(user, pass)
                )
            }
            val code = c.responseCode
            if (code !in 200..299) {
                c.disconnect()
                false
            } else {
                c.inputStream.use { i ->
                    out.outputStream().use { o -> i.copyTo(o) }
                }
                c.disconnect()
                out.length() > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun basicCred(user: String, pass: String): String =
        Base64.encodeToString(
            "$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
}
