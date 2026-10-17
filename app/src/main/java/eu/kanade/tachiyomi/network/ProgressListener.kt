package eu.kanade.tachiyomi.network

/** Progress callback used by [ProgressResponseBody]. */
fun interface ProgressListener {
    fun update(bytesRead: Long, contentLength: Long, done: Boolean)
}
