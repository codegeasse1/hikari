package com.hikari.app.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream

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

    fun openDownloadStream(
        context: Context,
        displayName: String,
        mime: String,
    ): Result<Pair<Uri, OutputStream>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.failure(IOException("direct streaming needs Android 10+"))
        }
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore refused the entry")
            try {
                val out = resolver.openOutputStream(uri)
                    ?: throw IOException("the entry could not be opened for writing")
                uri to out
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }
    }

    /** Marks a streamed entry complete and returns its display name
     *  (MediaStore renames collisions, so the row's own name is read back). */
    fun finishDownload(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        runCatching {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, values, null, null)
        }
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull().takeIf { !it.isNullOrBlank() } ?: ""
    }

    /** Deletes a streamed entry after a failed write. */
    fun abortDownload(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
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
