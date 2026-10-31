package eu.kanade.tachiyomi.source.model

import android.net.Uri
import eu.kanade.tachiyomi.network.ProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

open class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    @Deprecated("Use a dedicated image-loading API instead") var uri: Uri? = null,
) : ProgressListener {

    val number: Int
        get() = index + 1

    private val _statusFlow = MutableStateFlow(State.QUEUE)
    val statusFlow = _statusFlow.asStateFlow()
    var status: State
        get() = _statusFlow.value
        set(value) {
            _statusFlow.value = value
        }

    private val _progressFlow = MutableStateFlow(0)
    val progressFlow = _progressFlow.asStateFlow()
    var progress: Int
        get() = _progressFlow.value
        set(value) {
            _progressFlow.value = value
        }

    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
        progress = if (contentLength > 0) {
            (100 * bytesRead / contentLength).toInt()
        } else {
            -1
        }
    }

    enum class State {
        QUEUE,
        LOAD_PAGE,
        DOWNLOAD_IMAGE,
        READY,
        ERROR,
    }
}
