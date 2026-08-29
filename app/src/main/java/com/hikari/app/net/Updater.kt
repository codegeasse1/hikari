package com.hikari.app.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hikari.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

object Updater {

    const val REPO = "codegeasse1/hikari"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"
    const val UPDATE_URL = "https://github.com/$REPO/releases/download/continuous/hikari-signed.apk"

    data class UpdateStatus(
        val available: Boolean,
        val currentVersion: String,
        val latestVersion: String,
        val apkUrl: String = UPDATE_URL,
        val releasesUrl: String = RELEASES_URL,
    )

    /** The app version the running APK was built with. */
    fun currentVersion(): String =
        try {
            BuildConfig.VERSION_NAME.ifBlank { "unknown" }
        } catch (e: Throwable) {
            "unknown"
        }

    /** The newest non-draft, non-prerelease `v*` release tag (excludes the
     *  `continuous` test channel), else null. */
    private fun latestReleaseTag(): String? =
        try {
            val json = Http.getString(
                "https://api.github.com/repos/$REPO/releases?per_page=10",
                mapOf("Accept" to "application/vnd.github+json"),
            ) ?: return null
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val rel = arr.optJSONObject(i) ?: continue
                if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) continue
                val tag = rel.optString("tag_name").ifBlank { continue }
                if (tag == "continuous") continue
                return tag
            }
            null
        } catch (e: Exception) {
            null
        }

    /** Dotted version comparison ("0.3.46") — is [a] newer than [b]? */
    private fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String): List<Long> =
            v.trim().trimStart('v').split('.')
                .mapNotNull { it.takeWhile { c -> c.isDigit() }.toLongOrNull() }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0L }
            val y = pb.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }

    /** Compares the running app version against the newest GitHub release. */
    suspend fun checkForUpdate(): UpdateStatus = withContext(Dispatchers.IO) {
        val current = currentVersion()
        val tag = latestReleaseTag()
        if (tag == null || current == "unknown") {
            return@withContext UpdateStatus(false, current, tag ?: "unknown")
        }
        val latest = tag.removePrefix("v").ifBlank { tag }
        UpdateStatus(
            available = isNewer(latest, current),
            currentVersion = current,
            latestVersion = latest,
            apkUrl = "https://github.com/$REPO/releases/download/$tag/hikari.apk",
        )
    }

    /**
     * Downloads the newest signed APK into the app's cache. [onProgress] is called
     * with (downloadedBytes, totalBytes) from a background thread — marshal to main
     * in the caller.
     */
    suspend fun download(
        context: Context,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        url: String = UPDATE_URL,
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates")
        val apk = File(dir, "hikari-update.apk")
        val ok = Http.downloadTo(url, apk) { d, t -> onProgress(d, t) }
        if (ok && apk.length() > 1_000_000) {
            Result.success(apk)
        } else {
            apk.delete()
            Result.failure(Exception("Download failed or file was empty"))
        }
    }

    fun canInstall(context: Context): Boolean =
        try {
            context.packageManager.canRequestPackageInstalls()
        } catch (e: Throwable) {
            true // very old API / unusual environment — try the install anyway
        }

    fun openInstallSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Hands the APK to the system package installer via a FileProvider URI. */
    fun install(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
