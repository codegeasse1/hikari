package com.hikari.app.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Saving a file into the phone's public Downloads folder — ONE implementation,
 * shared by Settings → Logs & diagnostics, the backup export, and anything else
 * that hands the user a file.
 *
 * There is one because the three copies this replaces all inserted into
 * `MediaStore.Downloads` with a display name and a MIME type and NOTHING ELSE,
 * and that insert is refused on Android 11 and later: without a `RELATIVE_PATH`
 * the row resolves to the ROOT of shared storage, which is not a directory an
 * app is allowed to write to, so `MediaProvider` rejects it
 * (`IllegalArgumentException: Primary directory null not allowed for
 * content://media/external/downloads`). The insert threw, `runCatching`
 * swallowed the reason, and Settings → Logs answered "Could not save the file"
 * for every log, at every size, on every tap — the reported bug. On Android 10
 * the same code worked, because the Downloads collection used to default its
 * relative path to `Download/`; that default is what Android 11 removed.
 *
 * The shape that works is the one this app's own download exporter already used
 * (see `download/DownloadEngine.writeToDownloads`): display name + MIME +
 * `RELATIVE_PATH = Download/Hikari` + `IS_PENDING`, write, then un-pend. The
 * legacy public directory is still tried for API < 29 (where
 * WRITE_EXTERNAL_STORAGE, capped at that level in the manifest, permits it) and
 * as a fallback above it, so an OEM whose MediaStore refuses for its own reasons
 * still gets its file.
 *
 * Failures carry the REASON, not just "could not save": a save that fails with
 * no explanation is what made this bug so hard to pin down.
 */
object DownloadsSaver {

    /** The folder saved files land in, under the public Downloads folder. */
    const val FOLDER = "Hikari"

    /** Where a saved file appears, for the message the user sees. */
    fun pathOf(name: String): String = "Downloads/$FOLDER/$name"

    /**
     * Copy [source] into `Downloads/Hikari` under [displayName]. The returned
     * path is what to tell the user; the failure carries the reason.
     */
    fun save(
        context: Context,
        source: File,
        displayName: String,
        mime: String,
    ): Result<String> {
        if (!source.exists() || source.length() == 0L) {
            return Result.failure(IOException("the file is empty"))
        }
        var modernReason: Throwable? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val modern = runCatching { saveViaMediaStore(context, source, displayName, mime) }
            if (modern.isSuccess) return Result.success(pathOf(modern.getOrThrow()))
            modernReason = modern.exceptionOrNull()
        }
        val legacy = runCatching { saveViaPublicDir(context, source, displayName, mime) }
        if (legacy.isSuccess) return Result.success(pathOf(legacy.getOrThrow()))
        val reason = listOfNotNull(modernReason?.message, legacy.exceptionOrNull()?.message)
            .firstOrNull { it.isNotBlank() }
            ?: "no writable Downloads folder"
        return Result.failure(IOException(reason))
    }

    /** [save] for content that exists in memory (the backup export). */
    fun saveBytes(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mime: String,
    ): Result<String> {
        val tmp = File(File(context.cacheDir, "outbox"), displayName)
        val result = runCatching {
            tmp.parentFile?.mkdirs()
            tmp.writeBytes(bytes)
            save(context, tmp, displayName, mime).getOrThrow()
        }
        runCatching { tmp.delete() }
        return result
    }

    private fun saveViaMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mime: String,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            // The line whose absence was the whole bug: without it the entry
            // resolves to the volume root and Android 11+ refuses the insert.
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the entry")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("the entry could not be opened for writing")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            // A pending entry that never completed would sit in Downloads as an
            // unreadable ghost, so it goes before the reason is handed back.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        // MediaStore renames a collision ("app.log (1)"), so the row's own name
        // is read back rather than assumed.
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: displayName
    }

    private fun saveViaPublicDir(
        context: Context,
        source: File,
        displayName: String,
        mime: String,
    ): String {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER,
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("the Downloads folder could not be created")
        if (!dir.canWrite()) throw IOException("the Downloads folder is not writable")
        val dest = uniqueIn(dir, displayName)
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        }
        return dest.name
    }

    /** `app.log` → `app (1).log` when the name is taken, as a plain file write
     *  cannot lean on MediaStore's own rename. */
    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists() && i < 500) {
            f = File(dir, "$stem ($i)$ext")
            i++
        }
        return f
    }
}
