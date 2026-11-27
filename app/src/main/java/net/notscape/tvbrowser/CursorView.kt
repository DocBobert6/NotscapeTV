package net.notscape.tvbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

/**
 * Onscreen cursor for the TV remote, drawn over the WebView.
 *
 * - D-pad moves the pointer; holding a direction accelerates
 *   (step grows from 24px to 120px while held).
 * - OK (DPAD_CENTER) taps: ACTION_DOWN is sent on press and
 *   ACTION_UP on release; holding OK past 500ms sends a long-press
 *   (DOWN held, then UP) so WebView long-press menus work.
 * - D-pad at a screen edge scrolls the page in that direction.
 * - The cursor hides after 5s idle and returns on the next D-pad
 *   press. A Bluetooth/USB mouse moves the cursor natively — the
 *   WebView handles mouse clicks itself, this view only mirrors
 *   the pointer position.
 *
 * The Activity owns key routing and calls [onDpad] / [onCenterDown] /
 * [onCenterUp]; this view only owns position, drawing and event
 * dispatch into the WebView.
 */
class CursorView(context: Context, private val webView: WebView) : View(context) {

    var cursorX: Float = 0f
        private set
    var cursorY: Float = 0f
        private set

    var visible: Boolean = false
        private set

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D9BE9")
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var centerDownTime: Long = 0L
    private var centerDownSent: Boolean = false
    private var lastMoveAt: Long = 0L
    private var moveStreak: Int = 0

    fun showAtCenter() {
        if (width == 0 || height == 0) {
            post { showAtCenter() }
            return
        }
        cursorX = width / 2f
        cursorY = height / 2f
        show()
    }

    fun show() {
        visible = true
        visibility = View.VISIBLE
        invalidate()
        armHideTimer()
    }

    fun hide() {
        visible = false
        visibility = View.GONE
        cancelCenterPress()
    }

    private fun armHideTimer() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { hide() }
        hideRunnable = r
        handler.postDelayed(r, 5_000)
    }

    /** D-pad direction: dx/dy are -1, 0 or 1. Returns true if handled. */
    fun onDpad(dx: Int, dy: Int): Boolean {
        if (!visible) {
            showAtCenter()
            return true
        }
        armHideTimer()
        val now = SystemClock.uptimeMillis()
        moveStreak = if (now - lastMoveAt < 300) (moveStreak + 1).coerceAtMost(8) else 0
        lastMoveAt = now
        val step = 24f + moveStreak * 12f
        val newX = (cursorX + dx * step).coerceIn(0f, (width - 1).toFloat())
        val newY = (cursorY + dy * step).coerceIn(0f, (height - 1).toFloat())
        // At an edge and still pushing outward: scroll the page instead.
        if (dx < 0 && cursorX <= 0f) webView.scrollBy(-(width / 2), 0)
        if (dx > 0 && cursorX >= width - 1f) webView.scrollBy(width / 2, 0)
        if (dy < 0 && cursorY <= 0f) webView.scrollBy(0, -(height / 2))
        if (dy > 0 && cursorY >= height - 1f) webView.scrollBy(0, height / 2)
        cursorX = newX
        cursorY = newY
        // Hover so links highlight under the pointer.
        dispatchHover()
        invalidate()
        return true
    }

    fun onCenterDown(): Boolean {
        if (!visible) {
            showAtCenter()
            return true
        }
        armHideTimer()
        centerDownTime = SystemClock.uptimeMillis()
        centerDownSent = true
        dispatchTouch(MotionEvent.ACTION_DOWN, centerDownTime)
        longPressRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            // Still held after 500ms: keep DOWN held, then release as a
            // long-press (WebView recognises the held DOWN + late UP).
            centerDownSent = false
            dispatchTouch(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
        }
        longPressRunnable = r
        handler.postDelayed(r, 500)
        return true
    }

    fun onCenterUp(): Boolean {
        if (!centerDownSent) return true
        centerDownSent = false
        longPressRunnable?.let { handler.removeCallbacks(it) }
        dispatchTouch(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
        armHideTimer()
        return true
    }

    private fun cancelCenterPress() {
        if (centerDownSent) {
            centerDownSent = false
            longPressRunnable?.let { handler.removeCallbacks(it) }
            dispatchTouch(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
        }
    }

    private fun dispatchTouch(action: Int, time: Long) {
        val ev = MotionEvent.obtain(time, time, action, cursorX, cursorY, 0)
        webView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun dispatchHover() {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, 0)
        webView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Mirror a mouse pointer so the ring follows it. */
    fun onMouseMove(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, (width - 1).toFloat())
        cursorY = y.coerceIn(0f, (height - 1).toFloat())
        if (!visible) {
            visible = true
            visibility = View.VISIBLE
        }
        invalidate()
        armHideTimer()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible) return
        canvas.drawCircle(cursorX, cursorY, 26f, shadowPaint)
        canvas.drawCircle(cursorX, cursorY, 22f, ringPaint)
        canvas.drawCircle(cursorX, cursorY, 6f, dotPaint)
    }
}
