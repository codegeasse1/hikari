package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.os.Parcelable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.viewpager.widget.DirectionalViewPager
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap

/**
 * Pager implementation that listens for tap events and allows temporarily disabling touch events
 * in order to work with child views that need to disable touch events on this parent. The pager
 * can also be declared to be vertical by creating it with [isHorizontal] to false. Ported from
 * chimahon's Pager (the e-Ink paths were dropped — Nekoread has no e-Ink setting).
 */
open class Pager(
    context: Context,
    isHorizontal: Boolean = true,
) : DirectionalViewPager(context, isHorizontal) {

    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    var isRestoring = false

    /** True for the R2L viewer; physical swipe direction is reversed. */
    var isRightToLeft = false

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeDownItem = 0
    private var manualSwipeActive = false
    private var manualSwipeEligible = true

    override fun onRestoreInstanceState(state: Parcelable?) {
        isRestoring = true
        val currentItem = currentItem
        super.onRestoreInstanceState(state)
        setCurrentItem(currentItem, false)
        isRestoring = false
    }

    /**
     * Gesture listener that implements tap and long tap events.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onDown(ev: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            tapListener?.invoke(ev)
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            val listener = longTapListener
            if (listener != null && listener.invoke(ev)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeDownItem = currentItem
                manualSwipeActive = false
                manualSwipeEligible = isHorizontal() && !currentPageConsumesHorizontalPan()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!manualSwipeActive && manualSwipeEligible) {
                    val dx = ev.x - swipeDownX
                    val dy = ev.y - swipeDownY
                    val slop = ViewConfiguration.get(context).scaledPagingTouchSlop
                    if (kotlin.math.abs(dx) > slop &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f
                    ) {
                        manualSwipeActive = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        runCatching {
                            MotionEvent.obtain(ev).apply {
                                action = MotionEvent.ACTION_CANCEL
                                super@Pager.dispatchTouchEvent(this)
                                recycle()
                            }
                        }
                        return true
                    }
                }
                if (manualSwipeActive) return true
            }
            MotionEvent.ACTION_UP -> {
                if (manualSwipeActive) {
                    val dx = ev.x - swipeDownX
                    val slop = ViewConfiguration.get(context).scaledPagingTouchSlop
                    val base = swipeDownItem
                    val next = if (kotlin.math.abs(dx) > slop) {
                        if (isRightToLeft) {
                            if (dx > 0f) base + 1 else base - 1
                        } else {
                            if (dx < 0f) base + 1 else base - 1
                        }
                    } else base
                    if (next != base && next >= 0 && next < (adapter?.count ?: 0)) {
                        setCurrentItem(next, true)
                    }
                    manualSwipeActive = false
                    manualSwipeEligible = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (manualSwipeActive) {
                    manualSwipeActive = false
                    manualSwipeEligible = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    private fun currentPageConsumesHorizontalPan(): Boolean {
        for (i in 0 until childCount) {
            val holder = getChildAt(i) as? PagerPageHolder ?: continue
            val image = holder.getImageView()
            return image is SubsamplingScaleImageView && image.scale > image.minScale + 0.01f
        }
        return false
    }

    /**
     * Whether the given [ev] should be intercepted. Only used to prevent crashes when child
     * views manipulate [requestDisallowInterceptTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Handles a touch event. Only used to prevent crashes when child views manipulate
     * [requestDisallowInterceptTouchEvent].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onTouchEvent(ev)
        } catch (e: NullPointerException) {
            false
        } catch (e: IndexOutOfBoundsException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Executes the given key event when this pager has focus. Just do nothing because the reader
     * already dispatches key events to the viewer and has more control than this method.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        // Disable viewpager's default key event handling
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }
}
