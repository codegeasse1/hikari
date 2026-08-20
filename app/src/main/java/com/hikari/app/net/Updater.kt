package com.hikari.app.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hikari.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object Updater {

    const val REPO = "codegeasse1/hikari"
    const val UPDATE_URL =
        "https://github.com/$REPO/releases/download/continuous/hikari-signed.apk"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"

    data class UpdateStatus(
        val available: Boolean,
        val currentSha: String,
        val latestSha: String,
        val apkUrl: String = UPDATE_URL,
        val releasesUrl: String = RELEASES_URL,
    )

    /** The commit the running APK was compiled from (CI-injected at build time). */
    fun currentSha(): String =
        try {
            BuildConfig.GIT_SHA.ifBlank { "unknown" }
        } catch (e: Throwable) {
            "unknown"
        }

    /** Compares main's HEAD commit against the commit this APK was built from. */
    suspend fun checkForUpdate(): UpdateStatus = withContext(Dispatchers.IO) {
        val current = currentSha()
        val latestSha = try {
            val json = Http.getString(
                "https://api.github.com/repos/$REPO/commits/main",
                mapOf("Accept" to "application/vnd.github+json"),
            ) ?: return@withContext UpdateStatus(false, current, "unknown")
            JSONObject(json).getString("sha")
        } catch (e: Exception) {
            return@withContext UpdateStatus(false, current, "unknown")
        }
        UpdateStatus(
            available = current != "unknown" && latestSha.isNotBlank() && latestSha != current,
            currentSha = current,
            latestSha = latestSha,
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
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates")
        val apk = File(dir, "hikari-update.apk")
        val ok = Http.downloadTo(UPDATE_URL, apk) { d, t -> onProgress(d, t) }
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
