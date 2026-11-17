package com.qi.phoneaudiobridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Online update (v2): the app checks a small JSON manifest hosted at a
 * FIXED canonical address (editable in Settings, persisted, so the user
 * can later move it to their own server):
 *
 *   {"android": {"version": "2.1.0", "url": "https://.../app.apk",
 *                "sha256": "<hex of the apk>", "notes": "更新说明"}}
 *
 * Flow: fetch manifest -> if version > APP_VERSION, show notes -> user
 * confirms -> download APK to cacheDir -> VERIFY SHA-256 (abort on
 * mismatch, never install) -> hand to the system installer.
 *
 * Installer route (framework-only, no AndroidX): REQUEST_INSTALL_PACKAGES
 * + the unknown-sources gate (canRequestPackageInstalls /
 * ACTION_MANAGE_UNKNOWN_APP_SOURCES) + a content:// URI served by
 * [TinyFileProvider], our own minimal ContentProvider — AndroidX
 * FileProvider is not available in this no-AndroidX project, and file://
 * URIs are rejected by the installer on API 24+.
 */
object UpdateChecker {
    const val APP_VERSION = "2.2.1"
    const val DEFAULT_UPDATE_MANIFEST_URL = "https://raw.githubusercontent.com/Qiguiqiang/soundrelay/main/update.json"

    /**
     * User-visible templates for fetch failures / the diagnostics summary.
     * This object must stay JVM-compilable WITHOUT Android resources (the
     * tools/update-harness-android harness compiles it standalone), so it
     * cannot reference R.string directly: the English defaults below keep
     * it usable headless, and MainActivity injects the localized strings
     * from resources at startup (see wireUpdateUi). Locale follows the
     * system via the normal values / values-en resource split.
     */
    class Strings(
        val summaryFailed: (String) -> String,
        val summaryNone: String,
        val errorHttp: (Int) -> String,
        val errorInvalidManifest: String,
        val errorNetwork: (String) -> String
    )

    @Volatile var strings: Strings = Strings(
        summaryFailed = { "Failed: $it" },
        summaryNone = "none",
        errorHttp = { "Server returned HTTP $it" },
        errorInvalidManifest = "Invalid update manifest (could not be parsed)",
        errorNetwork = { "Network unreachable: $it" }
    )

    data class UpdateInfo(val version: String, val url: String, val sha256: String, val notes: String)

    fun parseManifest(json: String): UpdateInfo? {
        val root = try { JSONObject(json) } catch (_: Exception) { return null }
        val a = root.optJSONObject("android") ?: return null
        val version = a.optString("version")
        val url = a.optString("url")
        val sha = a.optString("sha256")
        if (version.isEmpty() || url.isEmpty() || sha.isEmpty()) return null
        return UpdateInfo(version, url, sha, a.optString("notes"))
    }

    /** Numeric dotted-version compare: is [remote] strictly newer than [local]? */
    fun isNewer(remote: String, local: String = APP_VERSION): Boolean {
        fun parts(v: String) = v.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }; val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    fun fetchManifest(manifestUrl: String): UpdateInfo? = fetchManifestReport(manifestUrl).info

    /**
     * Full check report (v2.2 defect B): the update button must NEVER be
     * a silent no-op, so every fetch returns what actually happened —
     * the URL tried, the HTTP status, the parsed versions of BOTH
     * entries, and a plain-language error when it failed. The UI renders
     * exactly one of: up-to-date / new-version / check-failed:<reason>.
     */
    data class FetchReport(
        val url: String,
        val httpCode: Int,               // -1 = no HTTP response at all
        val info: UpdateInfo?,           // android entry
        val partnerVersion: String?,     // windows entry version, same manifest
        val error: String?               // plain-language failure reason
    ) {
        /** One-line diagnostics summary (URL + HTTP status + versions). */
        val summary: String
            get() = "GET $url → " + when {
                error != null -> strings.summaryFailed(error)
                else -> "HTTP $httpCode · android=${info?.version ?: strings.summaryNone} · windows=${partnerVersion ?: strings.summaryNone}"
            }
    }

    /** Diagnostics of the most recent check (shown in the update card). */
    @Volatile var lastCheckSummary: String = ""
        private set

    fun parsePartnerVersion(json: String): String? {
        val root = try { JSONObject(json) } catch (_: Exception) { return null }
        val w = root.optJSONObject("windows") ?: return null
        return w.optString("version").ifEmpty { null }
    }

    fun fetchManifestReport(manifestUrl: String): FetchReport {
        var conn: HttpURLConnection? = null
        val report = try {
            conn = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AirBridge-Android/$APP_VERSION")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                FetchReport(manifestUrl, code, null, null, strings.errorHttp(code))
            } else {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val info = parseManifest(text)
                if (info == null) {
                    FetchReport(manifestUrl, code, null, null, strings.errorInvalidManifest)
                } else {
                    FetchReport(manifestUrl, code, info, parsePartnerVersion(text), null)
                }
            }
        } catch (e: Exception) {
            FetchReport(manifestUrl, -1, null, null, strings.errorNetwork(e.message ?: e.javaClass.simpleName))
        } finally {
            conn?.disconnect()
        }
        lastCheckSummary = report.summary
        try { android.util.Log.i("AirBridgeUpdate", report.summary) } catch (_: Throwable) {}
        return report
    }

    /** Newest windows version in the same manifest (partner-state display). */
    fun fetchPartnerVersion(manifestUrl: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AirBridge-Android/$APP_VERSION")
            }
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val w = try { JSONObject(text).optJSONObject("windows") } catch (_: Exception) { null }
            w?.optString("version")?.ifEmpty { null }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun downloadTo(fileUrl: String, dest: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AirBridge-Android/$APP_VERSION")
            }
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.length() > 0
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The tamper gate: an APK is installed ONLY if its SHA-256 matches the manifest. */
    fun verify(file: File, expectedSha256: String): Boolean =
        file.exists() && sha256Hex(file).equals(expectedSha256.trim(), ignoreCase = true)
}

/** Hands a verified APK to the system installer via our own provider URI. */
object UpdateInstaller {
    const val AUTHORITY = "com.qi.phoneaudiobridge.fileprovider"
    @Volatile var sharedFile: File? = null

    fun installIntent(context: Context, apk: File): Intent {
        sharedFile = apk
        val uri = Uri.parse("content://$AUTHORITY/${apk.name}")
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

/**
 * Minimal ContentProvider serving exactly the one file registered in
 * [UpdateInstaller.sharedFile]. This replaces AndroidX FileProvider
 * (unavailable in this framework-only project) for the install intent.
 */
class TinyFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = UpdateInstaller.sharedFile
        if (f == null || !f.exists() || uri.lastPathSegment != f.name) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
