package app.orb.tracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import app.orb.tracker.core.OrbState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** A point on a wall (screen edge or corner) in screen pixels, and the direction into the screen. */
data class Anchor(val x: Float, val y: Float, val nx: Int, val ny: Int) {
    val isCorner get() = nx != 0 && ny != 0
}

/**
 * The Orb design system's wall orb for Android, a port of ui/src/orb/WallOrb.ts.
 *
 * Its centre rests on a screen edge (or in a corner) and the window edge clips it: a half
 * circle on an edge, a quarter in a corner. Pressed, droplets drift around its rim and it
 * swells towards the long-press; pulled off, it becomes a ball on a neck of liquid and
 * flows to the nearest edge or corner when let go. At rest it is flat shapes: the goo
 * effect (blur + alpha threshold, Android 12+) and the frame loop run only while touched.
 */
@SuppressLint("ViewConstructor")
class WallOrb(context: Context, private val r: Float, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        fun onClick()
        fun onLongPress()
        /** Pressed: the host can grow its window to the whole screen for the drag. */
        fun onPressStart()
        /** It flowed to a new edge or corner. */
        fun onMove(anchor: Anchor)
        /** Back at rest: the host can shrink its window again. */
        fun onSettled()
    }

    private val theme = Theme.of(context)

    /** Where the orb rests, and the screen area it may move in, both in screen pixels. */
    var anchor = Anchor(0f, 0f, -1, 0)
    var bounds = RectF()
    /** The screen position of this view's top-left corner (its window's origin). */
    private var ox = 0f
    private var oy = 0f

    var state: OrbState = OrbState.IDLE
        set(value) {
            if (field == value) return
            val had = stateSet
            field = value
            stateSet = true
            fill.color = theme.orbFill(value)
            fill.alpha = if (value == OrbState.IDLE) 153 else 255
            glyphPaint.color = theme.onOrb(value)
            if (had && !reduceMotion()) {
                jelly = 0f
                if (value == OrbState.BAD) pulse = 0f
                wake()
                loop()
            }
            invalidateAll()
        }
    private var stateSet = false

    // the ball's offset from the anchor, and its velocity
    private var mx = 0f
    private var my = 0f
    private var vx = 0f
    private var vy = 0f
    private var springing = false
    private var pressed = false
    private var moved = false
    private var longFired = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var pressAt = 0L
    private var hv = 0f // how alive the liquid is (0..1)
    private var hold = 0f // how far a press has built towards the long-press (0..1)
    private var phase = 0f // one steady clock for the waves
    private var jelly = -1f
    private var pulse = -1f
    private var live = false
    private var looping = false
    private var lastFrame = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.orbFill(state); alpha = 153 }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = theme.onOrb(state)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = theme.orbBad }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val gooView = object : View(context) { override fun onDraw(canvas: Canvas) = drawGoo(canvas) }
    private val glyphView = object : View(context) { override fun onDraw(canvas: Canvas) = drawGlyph(canvas) }
    private val longPress = Runnable {
        if (pressed && !moved) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener.onLongPress()
        }
    }
    private val sleep = Runnable { setLive(false) }

    init {
        addView(gooView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glyphView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = "Sapphier orb"
    }

    /** The host moved its window: draw relative to the new origin. */
    fun setOrigin(x: Float, y: Float) {
        ox = x
        oy = y
        invalidateAll()
    }

    private fun invalidateAll() {
        gooView.invalidate()
        glyphView.invalidate()
    }

    private fun reduceMotion() =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun setLive(on: Boolean) {
        if (live == on) return
        live = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) gooView.setRenderEffect(if (on) gooEffect() else null)
    }

    private fun gooEffect(): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val blur = RenderEffect.createBlurEffect(r * 0.2f, r * 0.2f, Shader.TileMode.DECAL)
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

    private fun rested() {
        if (pressed || springing) return
        listener.onSettled()
        removeCallbacks(sleep)
        postDelayed(sleep, 120)
    }

    /** The nearest edge or corner to a screen point. */
    private fun snap(px: Float, py: Float): Anchor {
        val b = bounds
        val zone = r * 2.6f
        val left = px - b.left < b.right - px
        val top = py - b.top < b.bottom - py
        val hd = if (left) px - b.left else b.right - px
        val vd = if (top) py - b.top else b.bottom - py
        val ex = if (left) b.left else b.right
        val ey = if (top) b.top else b.bottom
        val nx = if (left) 1 else -1
        val ny = if (top) 1 else -1
        return when {
            hd < zone && vd < zone -> Anchor(ex, ey, nx, ny)
            hd <= vd -> Anchor(ex, py.coerceIn(b.top + r, b.bottom - r), nx, 0)
            else -> Anchor(px.coerceIn(b.left + r, b.right - r), ey, 0, ny)
        }
    }

    /** Runs frames only while something moves. */
    private fun loop() {
        if (looping) return
        looping = true
        lastFrame = System.nanoTime()
        postOnAnimation(object : Runnable {
            override fun run() {
                val now = System.nanoTime()
                val dt = min((now - lastFrame) / 1e9f, 0.05f)
                lastFrame = now
                var active = false
                if (springing) {
                    vx = (vx - mx * 0.12f) * 0.82f
                    vy = (vy - my * 0.12f) * 0.82f
                    mx += vx
                    my += vy
                    if (abs(mx) + abs(my) + abs(vx) + abs(vy) < 0.4f) {
                        mx = 0f; my = 0f; vx = 0f; vy = 0f
                        springing = false
                    } else active = true
                }
                val engaged = pressed && !moved
                hv += ((if (engaged) 1f else 0f) - hv) * min(dt * 9f, 1f)
                val holdTarget = if (engaged) ((System.currentTimeMillis() - pressAt) / 550f).coerceIn(0f, 1f) else 0f
                hold += (holdTarget - hold) * min(dt * (if (holdTarget > hold) 10f else 4f), 1f)
                phase += dt
                if (engaged || hv > 0.01f || hold > 0.01f) active = true else { hv = 0f; hold = 0f }
                if (jelly >= 0f) { jelly += dt / 0.64f; if (jelly >= 1f) jelly = -1f else active = true }
                if (pulse >= 0f) { pulse += dt / 1.6f; if (pulse >= 3f) pulse = -1f else active = true }
                invalidateAll()
                if (active || pressed) postOnAnimation(this) else {
                    looping = false
                    rested()
                }
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val bx = anchor.x + mx - ox
                val by = anchor.y + my - oy
                if (hypot(e.x - bx, e.y - by) > r * 1.15f) return false
                springing = false
                pressed = true
                moved = false
                longFired = false
                // raw (screen) coordinates, so the window can grow or move mid-drag
                downX = e.rawX - mx; downY = e.rawY - my
                lastX = e.rawX; lastY = e.rawY; lastT = e.eventTime
                pressAt = System.currentTimeMillis()
                wake()
                listener.onPressStart()
                postDelayed(longPress, 550)
                loop()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressed) return false
                val x = e.rawX - downX
                val y = e.rawY - downY
                if (!moved && hypot(x - mx, y - my) < slop) return true
                moved = true
                removeCallbacks(longPress)
                val dt = maxOf(e.eventTime - lastT, 8L).toFloat()
                vx = (e.rawX - lastX) / dt * 16f
                vy = (e.rawY - lastY) / dt * 16f
                lastX = e.rawX; lastY = e.rawY; lastT = e.eventTime
                // keep the ball's centre on the screen
                mx = (anchor.x + x).coerceIn(bounds.left, bounds.right) - anchor.x
                my = (anchor.y + y).coerceIn(bounds.top, bounds.bottom) - anchor.y
                invalidateAll()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!pressed) return false
                removeCallbacks(longPress)
                pressed = false
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    if (moved) {
                        // flow to the nearest edge or corner; the ball keeps its place and springs there
                        val px = anchor.x + mx
                        val py = anchor.y + my
                        anchor = snap(px, py)
                        mx = px - anchor.x
                        my = py - anchor.y
                        listener.onMove(anchor)
                    } else if (!longFired) {
                        performClick()
                        listener.onClick()
                    }
                }
                if (reduceMotion()) { mx = 0f; my = 0f; vx = 0f; vy = 0f } else springing = true
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
        val ax = anchor.x - ox
        val ay = anchor.y - oy
        val bx = ax + mx
        val by = ay + my
        val dist = hypot(mx, my)
        val speed = hypot(vx, vy)
        val nl = hypot(anchor.nx.toFloat(), anchor.ny.toFloat())
        val ang0 = atan2(anchor.ny / nl, anchor.nx / nl)

        // liquid left on the wall, and the neck that joins it to the ball, thinning until it snaps
        if (dist > 2f) {
            val f = min(dist / (r * 4f), 1f)
            c.drawCircle(ax, ay, r * 0.85f * (1 - f * 0.7f), fill)
            for (t in floatArrayOf(0.3f, 0.55f, 0.8f)) {
                val k = (1 - f * f) * (0.95f - 0.35f * abs(t - 0.5f))
                if (k > 0.02f) c.drawCircle(ax + mx * t, ay + my * t, r * 0.55f * k, fill)
            }
        }

        // pressed: three droplets drift around the rim, then gather into the swelling ball
        val h = if (dist < 2f) hv else 0f
        if (h > 0.01f) {
            val calm = 1 - hold
            val buds = arrayOf(-0.62f to 0.34f, 0.66f to 0.28f, 0.02f to 0.22f)
            for ((i, b) in buds.withIndex()) {
                val (da, s) = b
                val ang = ang0 + da + 0.3f * calm * sin(phase * (1.5f + i * 0.35f) + i * 2.1f)
                val d = r * (0.92f + 0.12f * calm * sin(phase * 2.2f + i * 1.3f) - hold * 0.45f) * h
                val rad = r * s * (0.85f + 0.22f * calm * sin(phase * 2f + i * 1.9f) + hold * 0.25f) * (0.4f + 0.6f * h)
                c.drawCircle(bx + cos(ang) * d, by + sin(ang) * d, rad, fill)
            }
        }

        // the ball: stretched along its motion; breathing and swelling evenly while pressed
        c.save()
        c.translate(bx, by)
        if (dist > 1f || speed > 0.5f) {
            val ang = (atan2(if (speed > 0.5f) vy else my, if (speed > 0.5f) vx else mx) * 180 / PI).toFloat()
            val st = 1 + min(dist / r * 0.1f + speed / r * 0.5f, 0.32f)
            c.rotate(ang); c.scale(st, 1 / st); c.rotate(-ang)
        } else if (h > 0.01f || hold > 0.01f) {
            val calm = 1 - hold
            val eased = hold * hold * (3 - 2 * hold)
            val breathe = 1 + 0.025f * sin(phase * 2.3f) * h * calm + eased * 0.12f
            val w = 0.035f * h * calm * sin(phase * 3.4f)
            val rot = (phase * 24f) % 360f
            c.scale(breathe, breathe)
            c.rotate(rot); c.scale(1 + w, 1 - w); c.rotate(-rot)
        }
        if (jelly >= 0f) jellyScale(jelly).let { (sx, sy) -> c.scale(sx, sy) }
        c.drawCircle(0f, 0f, r, fill)
        c.restore()
    }

    private val glyph = Path()

    private fun drawGlyph(c: Canvas) {
        val bx = anchor.x - ox + mx
        val by = anchor.y - oy + my
        val dist = hypot(mx, my)
        if (pulse >= 0f) {
            val p = pulse % 1f
            ringPaint.alpha = ((1 - p) * 0.7f * 255).toInt()
            ringPaint.strokeWidth = context.dp(14f) * p
            c.drawCircle(bx, by, r + ringPaint.strokeWidth / 2, ringPaint)
        }
        glyph.reset()
        when (state) {
            OrbState.GOOD -> { glyph.moveTo(7f, 12.5f); glyph.lineTo(10f, 15.5f); glyph.lineTo(17f, 8.5f) }
            OrbState.BAD -> { glyph.moveTo(12f, 6.5f); glyph.lineTo(12f, 13.5f); glyph.moveTo(12f, 17.5f); glyph.lineTo(12f, 17.51f) }
            OrbState.NEUTRAL -> { glyph.moveTo(7f, 12f); glyph.lineTo(17f, 12f) }
            OrbState.IDLE -> { glyph.moveTo(9f, 12f); glyph.lineTo(9.01f, 12f); glyph.moveTo(15f, 12f); glyph.lineTo(15.01f, 12f) }
        }
        // in the middle of the visible part (4R/3π along each inward axis), centred once free
        val detach = (dist / (r * 1.2f)).coerceIn(0f, 1f)
        val off = 4 * r / (3 * PI.toFloat()) * (1 - detach)
        val g0 = if (anchor.isCorner) r * 0.42f else r * 0.5f
        val g = g0 + (r * 0.62f - g0) * detach
        c.save()
        c.translate(bx + anchor.nx * off - g / 2, by + anchor.ny * off - g / 2)
        c.scale(g / 24f, g / 24f)
        glyphPaint.strokeWidth = 2.4f
        c.drawPath(glyph, glyphPaint)
        c.restore()
    }
}
