package com.hikari.app.dub

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.PixelCopy
import android.view.PixelCopy.OnPixelCopyFinishedListener
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HardSubDub(
    private val activity: Activity,
    private val view: PlayerView,
    private val player: () -> ExoPlayer?,
    private val dub: AiDubManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var overlay: TextView? = null
    private var lastText = ""
    private var emptyStreak = 0

    private val latin by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val chinese by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    fun onPlayingChanged(playing: Boolean) {
        if (playing && dub.enabled && dub.hardsub) start()
        else stopLoop()
    }

    fun release() {
        stopLoop()
        scope.cancel()
        try {
            latin.close()
        } catch (e: Exception) {
        }
        try {
            chinese.close()
        } catch (e: Exception) {
        }
        try {
            view.removeView(overlay)
        } catch (e: Exception) {
        }
        overlay = null
    }

    private fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    private fun stopLoop() {
        job?.cancel()
        job = null
        lastText = ""
        emptyStreak = 0
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            delay(700)
        }
    }

    private suspend fun tick() {
        val p = player() ?: return
        if (!p.isPlaying) return
        // A real subtitle track is talking: never double it.
        if (System.currentTimeMillis() - dub.providerCueAt < 2000) {
            hideOverlay()
            return
        }
        val band = captureBand() ?: return
        try {
            val text = withContext(Dispatchers.IO) { ocrBand(band) }
            if (text.isBlank()) {
                if (++emptyStreak >= 2) {
                    hideOverlay()
                    lastText = ""
                }
                return
            }
            emptyStreak = 0
            if (text == lastText) return
            lastText = text
            val L = dub.currentLang()
            val out = withContext(Dispatchers.IO) { dub.translateForDub(text, L) }
            if (out.isNullOrBlank()) return
            showOverlay(out.trim())
            dub.speakDirect(out.trim(), L)
        } finally {
            try {
                if (!band.isRecycled) band.recycle()
            } catch (e: Exception) {
            }
        }
    }

    private suspend fun captureBand(): Bitmap? {
        val sv: View = view.videoSurfaceView ?: return null
        val w = sv.width
        val h = sv.height
        if (w < 48 || h < 48) return null
        val full: Bitmap = if (sv is TextureView) {
            sv.bitmap ?: return null
        } else if (sv is SurfaceView) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val ok = suspendCancellableCoroutine { c ->
                try {
                    PixelCopy.request(
                        sv.holder.surface, bmp,
                        OnPixelCopyFinishedListener { res ->
                            if (!c.isCompleted) c.resume(res == PixelCopy.SUCCESS)
                        },
                        Handler(Looper.getMainLooper()),
                    )
                } catch (e: Exception) {
                    if (!c.isCompleted) c.resume(false)
                }
            }
            if (!ok) {
                try {
                    bmp.recycle()
                } catch (e: Exception) {
                }
                return null
            }
            bmp
        } else return null
        try {
            val top = (full.height * 0.64).toInt()
            val bandH = full.height - top
            if (bandH < 8) return null
            var band = Bitmap.createBitmap(full, 0, top, full.width, bandH)
            if (band.width > 960) {
                val s = 960f / band.width
                val small = Bitmap.createScaledBitmap(
                    band, 960,
                    (band.height * s).toInt().coerceAtLeast(8), true,
                )
                try {
                    band.recycle()
                } catch (e: Exception) {
                }
                band = small
            }
            return band
        } finally {
            try {
                if (!full.isRecycled) full.recycle()
            } catch (e: Exception) {
            }
        }
    }

    private data class Ln(val t: String, val y: Float)

    private suspend fun ocrBand(band: Bitmap): String {
        val img = InputImage.fromBitmap(band, 0)
        val a = runCatching { latin.process(img).awaitTask() }.getOrNull()
        val b = runCatching { chinese.process(img).awaitTask() }.getOrNull()
        val lines = ArrayList<Ln>()
        a?.textBlocks?.forEach { blk ->
            blk.lines.forEach { l ->
                val t = l.text.trim()
                if (t.isNotEmpty()) lines.add(Ln(t, l.boundingBox?.centerY()?.toFloat() ?: 0f))
            }
        }
        b?.textBlocks?.forEach { blk ->
            blk.lines.forEach { l ->
                val t = l.text.trim()
                if (t.isNotEmpty()) lines.add(Ln(t, l.boundingBox?.centerY()?.toFloat() ?: 0f))
            }
        }
        if (lines.isEmpty()) return ""
        // English first: it is the better translation pivot, and in the
        // dual-sub layout it sits above the Chinese line.
        val latinLines = lines
            .filter { ln -> ln.t.any { ch -> ch in 'a'..'z' || ch in 'A'..'Z' } }
            .sortedBy { it.y }
        val use = if (latinLines.isNotEmpty()) latinLines else lines.sortedBy { it.y }
        return use.joinToString(" ") { it.t }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun Task<com.google.mlkit.vision.text.Text>.awaitTask():
        com.google.mlkit.vision.text.Text = suspendCancellableCoroutine { c ->
        addOnSuccessListener { v -> if (!c.isCompleted) c.resume(v) }
        addOnFailureListener { e -> if (!c.isCompleted) c.resumeWithException(e ?: Exception("ocr failed")) }
    }

    private fun showOverlay(text: String) {
        var tv = overlay
        if (tv == null) {
            val d = view.resources.displayMetrics.density
            tv = TextView(view.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.WHITE)
                setShadowLayer(4f * d, 0f, 2f * d, Color.BLACK)
                setBackgroundColor(0xAA000000.toInt())
                val pad = (8f * d).toInt()
                setPadding(pad, (4f * d).toInt(), pad, (4f * d).toInt())
                gravity = Gravity.CENTER
            }
            view.addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply {
                    bottomMargin = (110f * d).toInt()
                    leftMargin = (24f * d).toInt()
                    rightMargin = (24f * d).toInt()
                },
            )
            overlay = tv
        }
        tv.text = text
        if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        try {
            overlay?.visibility = View.GONE
        } catch (e: Exception) {
        }
    }
}
