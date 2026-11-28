package com.callagent.gateway.update

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The release feed: what the newest published build is.
 *
 * The builds live in the project's own release feed, so the phone asks the
 * same place everyone else does.  The feed is public and the answer is a
 * small JSON document.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** Where the newest release is announced. */
    const val RELEASES_URL =
        "https://api.github.com/repos/GenaLC/android-tools/releases/latest"

    /** Who is asking.  The feed refuses a request without one. */
    private const val USER_AGENT = "devicetools"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** What a release carries. */
    data class Release(
        val version: String,
        val apkUrl: String,
        val notes: String,
        val sizeBytes: Long
    )

    /**
     * The newest published release, or null when the feed cannot be read.
     *
     * A feed that cannot be read is not a reason to stop: the gateway has to
     * work without it, and the check is made again at the next start.
     */
    fun fetch(log: (String) -> Unit): Release? {
        return try {
            val json = JSONObject(get(RELEASES_URL))
            val version = json.optString("tag_name").trim()
            if (version.isEmpty()) {
                log("Update: the feed names no version")
                return null
            }
            val apk = apkAssetOf(json.optJSONArray("assets") ?: JSONArray())
            if (apk == null) {
                log("Update: $version carries no application file")
                return null
            }
            Release(
                version = version,
                apkUrl = apk.url,
                notes = json.optString("body"),
                sizeBytes = apk.size
            )
        } catch (e: Exception) {
            Log.w(TAG, "The release feed could not be read: ${e.message}")
            log("Update: the release feed could not be read (${e.message})")
            null
        }
    }

    /** The application file of a release, with its size. */
    private data class Asset(val url: String, val size: Long)

    /**
     * The one asset that is the application itself.
     *
     * A release carries two files: the application and the Magisk module
     * that carries it.  Only the first one can be put over the running
     * application.
     */
    private fun apkAssetOf(assets: JSONArray): Asset? {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!AppVersion.isApkAsset(asset.optString("name"))) continue
            val url = asset.optString("browser_download_url")
            if (url.isEmpty()) continue
            return Asset(url, asset.optLong("size", 0L))
        }
        return null
    }

    /** Read a URL. */
    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
