package app.orb.tracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import app.orb.tracker.core.OrbState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The Orb design system's liquid orb for Android, docked to the right screen edge.
 *
 * At rest it is flat shapes. While touched, a RenderEffect goo filter (blur + alpha
 * threshold, Android 12+) melts the ball, tether and droplets together and a frame-driven
 * spring wobbles it home; both stop ~0.4 s after it settles. Older Android versions get
 * the same motion without the melting. Mirrors ui/src/orb/LiquidOrb.tsx.
 */
@SuppressLint("ViewConstructor")
class LiquidOrb(context: Context, private val size: Float, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        fun onClick()
        fun onLongPress()
        /** Pressed: the host can grow its window to make room for the drag. */
        fun onPressStart()
        /** Back at rest after a press or drag. */
        fun onSettled()
        /** A drag ended at (dx, dy) from rest; return how much of dy the host absorbed. */
        fun onDragEnd(dx: Float, dy: Float): Float
    }

    private val theme = Theme.of(context)
    private val margin = context.dp(8f)
    var state: OrbState = OrbState.IDLE
        set(value) {
            if (field == value) return
            val wasSet = stateSet
            field = value
            stateSet = true
            fillPaint.color = theme.orbFill(value)
            glyphPaint.color = theme.onOrb(value)
            if (wasSet && !reduceMotion()) {
                jelly = 0f
                if (value == OrbState.BAD) pulse = 0f
                wake()
                loop()
            }
            gooView.invalidate()
            glyphView.invalidate()
        }
    private var stateSet = false

    // motion, in px from rest
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var springing = false
    private var pressed = false
    private var moved = false
    private var longPressed = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var buds = 0f // 0 = hidden in the ball, 1 = budded out
    private var jelly = -1f // 0..1 while wobbling after a state change
    private var pulse = -1f // 0..3 while the red ring pulses
    private var live = false
    private var looping = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.orbFill(state) }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = theme.onOrb(state)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = theme.orbBad }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val cx get() = width - margin - size / 2
    private val cy get() = height / 2f

    private val gooView = object : View(context) {
        override fun onDraw(canvas: Canvas) = drawGoo(canvas)
    }
    private val glyphView = object : View(context) {
        override fun onDraw(canvas: Canvas) = drawGlyph(canvas)
    }

    private val longPress = Runnable {
        if (pressed && !moved) {
            longPressed = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            listener.onLongPress()
        }
    }
    private val sleep = Runnable { setLive(false) }

    init {
        addView(gooView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glyphView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = "Orb"
    }

    private fun reduceMotion() =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun setLive(on: Boolean) {
        if (live == on) return
        live = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            gooView.setRenderEffect(if (on) gooEffect() else null)
        }
    }

    private fun gooEffect(): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val r = maxOf(3f, size * 0.14f)
        val blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL)
        // alpha' = 22·alpha − 9·255: a hard edge where the blurred shapes overlap enough
        val threshold = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 22f, -9f * 255f,
        )))
        return RenderEffect.createChainEffect(RenderEffect.createColorFilterEffect(threshold), blur)
    }

    private fun wake() {
        removeCallbacks(sleep)
        if (!reduceMotion()) setLive(true)
    }

    private fun sleepSoon(ms: Long = 420) {
        removeCallbacks(sleep)
        postDelayed(sleep, ms)
    }

    /** Runs frames only while something moves. */
    private fun loop() {
        if (looping) return
        looping = true
        postOnAnimation(object : Runnable {
            override fun run() {
                var active = false
                if (springing) {
                    vx = (vx - x * 0.12f) * 0.82f
                    vy = (vy - y * 0.12f) * 0.82f
                    x += vx
                    y += vy
                    if (abs(x) + abs(y) + abs(vx) + abs(vy) < 0.4f) {
                        x = 0f; y = 0f; vx = 0f; vy = 0f
                        springing = false
                        listener.onSettled()
                    } else active = true
                }
                val budTarget = if (pressed && !moved) 1f else 0f
                if (abs(buds - budTarget) > 0.01f) {
                    buds += (budTarget - buds) * 0.22f
                    active = true
                } else buds = budTarget
                if (jelly >= 0f) {
                    jelly += 16f / 640f
                    if (jelly >= 1f) jelly = -1f else active = true
                }
                if (pulse >= 0f) {
                    pulse += 16f / 1600f
                    if (pulse >= 3f) pulse = -1f else active = true
                }
                gooView.invalidate()
                glyphView.invalidate()
                if (active) postOnAnimation(this) else {
                    looping = false
                    if (!pressed) sleepSoon(120)
                }
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot(e.x - (cx + x), e.y - (cy + y)) > size * 0.75f) return false
                springing = false
                pressed = true
                moved = false
                longPressed = false
                // raw (screen) coordinates, so the window can grow or move mid-drag
                downX = e.rawX - x; downY = e.rawY - y
                lastX = e.rawX; lastY = e.rawY; lastT = e.eventTime
                wake()
                listener.onPressStart()
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                loop()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressed) return false
                val nx = e.rawX - downX
                val ny = e.rawY - downY
                if (!moved && hypot(nx - x, ny - y) < slop) return true
                moved = true
                removeCallbacks(longPress)
                val dt = maxOf(e.eventTime - lastT, 8L).toFloat()
                vx = (e.rawX - lastX) / dt * 16f
                vy = (e.rawY - lastY) / dt * 16f
                lastX = e.rawX; lastY = e.rawY; lastT = e.eventTime
                x = nx; y = ny
                gooView.invalidate()
                glyphView.invalidate()
                loop()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!pressed) return false
                removeCallbacks(longPress)
                pressed = false
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    if (moved) y -= listener.onDragEnd(x, y)
                    else if (!longPressed) { performClick(); listener.onClick() }
                }
                if (reduceMotion()) {
                    x = 0f; y = 0f; vx = 0f; vy = 0f
                    listener.onSettled()
                } else springing = true
                loop()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun jellyScale(t: Float): Pair<Float, Float> {
        // the design system's keyframes: 1 → (1.16, .86) → (.92, 1.08) → (1.04, .97) → 1
        val keys = arrayOf(0f to (1f to 1f), 0.3f to (1.16f to 0.86f), 0.55f to (0.92f to 1.08f), 0.8f to (1.04f to 0.97f), 1f to (1f to 1f))
        for (i in 1 until keys.size) {
            val (t1, s1) = keys[i]
            val (t0, s0) = keys[i - 1]
            if (t <= t1) {
                val k = (t - t0) / (t1 - t0)
                return (s0.first + (s1.first - s0.first) * k) to (s0.second + (s1.second - s0.second) * k)
            }
        }
        return 1f to 1f
    }

    private fun drawGoo(c: Canvas) {
        if (state == OrbState.IDLE) fillPaint.alpha = 153 else fillPaint.alpha = 255
        val dist = hypot(x, y)
        // tether to the screen edge
        val th = size * 0.55f
        c.drawRoundRect(cx, cy - th / 2, width + margin, cy + th / 2, th / 2, th / 2, fillPaint)
        // a neck of droplets that thins as the ball is pulled, then snaps
        if (dist > 2f) {
            val f = min(dist / (size * 2.6f), 1f)
            for (t in floatArrayOf(0.28f, 0.52f, 0.76f)) {
                val k = (1 - f * f) * (0.9f - 0.3f * abs(t - 0.5f))
                if (k > 0.02f) c.drawCircle(cx + x * t, cy + y * t, size * 0.72f * k / 2, fillPaint)
            }
        }
        // droplets that bud toward the screen while pressed
        if (buds > 0.01f) {
            for ((bx, by, bs) in listOf(Triple(0.52f, -0.22f, 0.34f), Triple(0.42f, 0.3f, 0.26f))) {
                c.drawCircle(cx + x - size * bx * buds, cy + y + size * by * buds, size * bs / 2 * (0.4f + 0.6f * buds), fillPaint)
            }
        }
        // the ball: stretched along its motion, squashed when pressed, jelly after a state change
        val speed = hypot(vx, vy)
        c.save()
        c.translate(cx + x, cy + y)
        if (dist > 1f || speed > 0.5f) {
            val ang = (atan2(if (speed > 0.5f) vy else y, if (speed > 0.5f) vx else x) * 180 / PI).toFloat()
            val st = 1 + min(dist / size * 0.18f + speed / size * 0.6f, 0.35f)
            c.rotate(ang); c.scale(st, 1 / st); c.rotate(-ang)
        } else if (pressed) c.scale(1.1f, 0.9f)
        if (jelly >= 0f) jellyScale(jelly).let { (sx, sy) -> c.scale(sx, sy) }
        c.drawCircle(0f, 0f, size / 2, fillPaint)
        c.restore()
    }

    private val glyph = Path()

    private fun drawGlyph(c: Canvas) {
        val bx = cx + x
        val by = cy + y
        if (pulse >= 0f) {
            val p = pulse % 1f
            ringPaint.alpha = ((1 - p) * 0.7f * 255).toInt()
            ringPaint.strokeWidth = context.dp(12f) * p
            c.drawCircle(bx, by, size / 2 + ringPaint.strokeWidth / 2, ringPaint)
        }
        glyph.reset()
        when (state) {
            OrbState.GOOD -> { glyph.moveTo(7f, 12.5f); glyph.lineTo(10f, 15.5f); glyph.lineTo(17f, 8.5f) }
            OrbState.BAD -> { glyph.moveTo(12f, 6.5f); glyph.lineTo(12f, 13.5f); glyph.moveTo(12f, 17.5f); glyph.lineTo(12f, 17.51f) }
            OrbState.NEUTRAL -> { glyph.moveTo(7f, 12f); glyph.lineTo(17f, 12f) }
            OrbState.IDLE -> { glyph.moveTo(9f, 12f); glyph.lineTo(9.01f, 12f); glyph.moveTo(15f, 12f); glyph.lineTo(15.01f, 12f) }
        }
        val g = size * 0.45f
        c.save()
        c.translate(bx - g / 2, by - g / 2)
        c.scale(g / 24f, g / 24f)
        glyphPaint.strokeWidth = 2.4f
        c.drawPath(glyph, glyphPaint)
        c.restore()
    }
}
