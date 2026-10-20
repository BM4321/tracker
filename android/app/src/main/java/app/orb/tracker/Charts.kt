package app.orb.tracker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import app.orb.tracker.core.Category
import app.orb.tracker.core.OrbState
import java.text.DateFormat
import java.util.Date

// Small views drawn on a canvas for Sapphier's screens: the balance bar, the day timeline,
// the week chart, limit tracks, the state dot and tab icons. Time to reduce is drawn with
// a diagonal hatch as well as red, so it never relies on colour alone.

private val clock: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
fun clockTime(ms: Long): String = clock.format(Date(ms))

/** A fill paint for a category; reduce gets a 45° hatch over its colour. */
fun categoryPaint(t: Theme, c: Category, density: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = t.chart(c)
    if (c == Category.BAD) {
        val n = (6 * density).toInt().coerceAtLeast(6)
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(t.chartBad)
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(72, 255, 255, 255); strokeWidth = 2 * density }
        cv.drawLine(0f, n.toFloat(), n.toFloat(), 0f, stripe)
        cv.drawLine(-n / 2f, n / 2f, n / 2f, -n / 2f, stripe)
        cv.drawLine(n / 2f, n * 1.5f, n * 1.5f, n / 2f, stripe)
        shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}

/** Good | reduce | other, as one bar with 2dp gaps. */
class BalanceBar(context: Context, private val t: Theme, private val parts: List<Pair<Category, Double>>) : View(context) {
    private val d = resources.displayMetrics.density
    private val paints = Category.entries.associateWith { categoryPaint(t, it, d) }
    override fun onDraw(c: Canvas) {
        val shown = parts.filter { it.second > 0 }
        val sum = shown.sumOf { it.second }.takeIf { it > 0 } ?: return
        val gap = 2 * d
        val usable = width - gap * (shown.size - 1)
        var x = 0f
        val path = Path()
        shown.forEachIndexed { i, (cat, m) ->
            val w = maxOf((usable * (m / sum)).toFloat(), 3 * d)
            val r = 4 * d
            val radii = floatArrayOf(
                if (i == 0) r else 0f, if (i == 0) r else 0f, if (i == shown.lastIndex) r else 0f, if (i == shown.lastIndex) r else 0f,
                if (i == shown.lastIndex) r else 0f, if (i == shown.lastIndex) r else 0f, if (i == 0) r else 0f, if (i == 0) r else 0f,
            )
            path.reset()
            path.addRoundRect(RectF(x, 0f, x + w, height.toFloat()), radii, Path.Direction.CW)
            c.drawPath(path, paints.getValue(cat))
            x += w + gap
        }
    }
}

data class Segment(val lane: Int, val start: Long, val end: Long, val category: Category, val label: String)

/** Today's sessions in one lane per device; tap a block to see what it was. */
@SuppressLint("ViewConstructor")
class TimelineView(
    context: Context,
    private val t: Theme,
    private val lanes: List<String>,
    private val segments: List<Segment>,
    private val from: Long,
    private val to: Long,
    private val onPick: (Segment?) -> Unit,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val paints = Category.entries.associateWith { categoryPaint(t, it, d) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.neutralSoft }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.inkMuted; textSize = 11 * d; isFakeBoldText = true }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.inkMuted; textSize = 10 * d; typeface = android.graphics.Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val now = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }
    private val pickedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.ink; style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private var picked: Segment? = null
    private val laneH = 22 * d
    private val gap = 8 * d
    private val left = 52 * d

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (lanes.size * (laneH + gap) + 16 * d).toInt())

    private fun x(time: Long) = left + (width - left) * ((time.coerceIn(from, to) - from).toFloat() / (to - from))

    override fun onDraw(c: Canvas) {
        lanes.forEachIndexed { i, name ->
            val top = i * (laneH + gap)
            c.drawText(name, 0f, top + laneH / 2 + 4 * d, label)
            c.drawRoundRect(left, top, width.toFloat(), top + laneH, 5 * d, 5 * d, track)
            for (s in segments.filter { it.lane == i }) {
                val r = RectF(x(s.start), top, maxOf(x(s.end), x(s.start) + 2 * d), top + laneH)
                c.drawRoundRect(r, 3 * d, 3 * d, paints.getValue(s.category))
                if (s == picked) c.drawRoundRect(r, 3 * d, 3 * d, pickedPaint)
            }
        }
        val nx = x(System.currentTimeMillis())
        c.drawRect(nx - d, 0f, nx + d, lanes.size * (laneH + gap) - gap + 4 * d, now)
        val hours = (to - from) / 3_600_000
        val startHour = 24 - hours
        var h = startHour
        while (h <= 24) {
            c.drawText(String.format("%02d", h % 24), x(from + (h - startHour) * 3_600_000), height - 2 * d, axis)
            h += 3
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val lane = (e.y / (laneH + gap)).toInt()
        val time = from + ((e.x - left) / (width - left) * (to - from)).toLong()
        val slack = (to - from) / 200
        picked = segments.firstOrNull { it.lane == lane && time >= it.start - slack && time <= it.end + slack }
        onPick(picked)
        invalidate()
        return true
    }
}

data class DayBar(val label: String, val good: Double, val bad: Double, val today: Boolean)

/** Good and reduce minutes stacked per day; tap a day to see its numbers. */
@SuppressLint("ViewConstructor")
class WeekChart(context: Context, private val t: Theme, private val days: List<DayBar>, private val onPick: (DayBar) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val good = categoryPaint(t, Category.GOOD, d)
    private val bad = categoryPaint(t, Category.BAD, d)
    private val grid = Paint().apply { color = t.line; strokeWidth = d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.inkMuted; textSize = 10 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val ylab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.inkMuted; textSize = 10 * d; typeface = android.graphics.Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT }
    private val todayDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }
    private val top = (days.maxOfOrNull { it.good + it.bad } ?: 0.0).coerceAtLeast(60.0).let { Math.ceil(it / 120) * 120 }
    private val left = 40 * d

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (200 * d).toInt())

    override fun onDraw(c: Canvas) {
        val plotB = height - 22 * d
        val plotT = 6 * d
        for (f in listOf(0.0, 0.5, 1.0)) {
            val y = (plotB - (plotB - plotT) * f).toFloat()
            c.drawLine(left, y, width.toFloat(), y, grid)
            c.drawText(formatMinutes(top * f), left - 6 * d, y + 3 * d, ylab)
        }
        val col = (width - left) / days.size
        val bw = minOf(col * 0.6f, 34 * d)
        days.forEachIndexed { i, day ->
            val cx = left + col * i + col / 2
            var y = plotB
            val gh = ((plotB - plotT) * (day.good / top)).toFloat()
            val bh = ((plotB - plotT) * (day.bad / top)).toFloat()
            if (gh > 0) { c.drawRoundRect(cx - bw / 2, y - gh, cx + bw / 2, y, 4 * d, 4 * d, good); y -= gh + 2 * d }
            if (bh > 0) c.drawRoundRect(cx - bw / 2, y - bh, cx + bw / 2, y, 4 * d, 4 * d, bad)
            c.drawText(day.label, cx, height - 4 * d, text)
            if (day.today) c.drawCircle(cx, plotB + 6 * d, 2 * d, todayDot)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && e.x > left) {
            val i = ((e.x - left) / ((width - left) / days.size)).toInt().coerceIn(0, days.lastIndex)
            onPick(days[i])
        }
        return true
    }
}

/** A limit track: used time against the limit marker. */
@SuppressLint("ViewConstructor")
class LimitTrack(context: Context, private val t: Theme, private val used: Double, private val limit: Double) : View(context) {
    private val d = resources.displayMetrics.density
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.line }
    private val fill = categoryPaint(t, Category.BAD, d)
    private val mark = Paint().apply { color = t.ink }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (14 * d).toInt())
    override fun onDraw(c: Canvas) {
        val scale = maxOf(limit * 1.25, used)
        val y = 3 * d
        val h = 8 * d
        c.drawRoundRect(0f, y, width.toFloat(), y + h, 4 * d, 4 * d, trackP)
        val w = (width * (used / scale)).toFloat()
        if (w > 0) c.drawRoundRect(0f, y, maxOf(w, h), y + h, 4 * d, 4 * d, fill)
        val mx = (width * (limit / scale)).toFloat()
        c.drawRect(mx - d, 0f, mx + d, height.toFloat(), mark)
    }
}

/** A thin share bar under a breakdown row. */
@SuppressLint("ViewConstructor")
class ShareBar(context: Context, private val t: Theme, private val pct: Float, private val c: Category) : View(context) {
    private val d = resources.displayMetrics.density
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.line }
    private val fill = categoryPaint(t, c, d)
    override fun onDraw(cv: Canvas) {
        val r = height / 2f
        cv.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, trackP)
        cv.drawRoundRect(0f, 0f, width * pct, height.toFloat(), r, r, fill)
    }
}

/** The orb's colour and glyph, for the "now" card. */
class StateDot(context: Context, private val t: Theme) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 2.4f }
    private val path = Path()
    var state = OrbState.IDLE
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        fill.color = t.orbFill(state)
        fill.alpha = if (state == OrbState.IDLE) 153 else 255
        glyph.color = t.onOrb(state)
        val r = minOf(width, height) / 2f
        c.drawCircle(width / 2f, height / 2f, r, fill)
        path.reset()
        when (state) {
            OrbState.GOOD -> { path.moveTo(7f, 12.5f); path.lineTo(10f, 15.5f); path.lineTo(17f, 8.5f) }
            OrbState.BAD -> { path.moveTo(12f, 6.5f); path.lineTo(12f, 13.5f); path.moveTo(12f, 17.5f); path.lineTo(12f, 17.51f) }
            OrbState.NEUTRAL -> { path.moveTo(7f, 12f); path.lineTo(17f, 12f) }
            OrbState.IDLE -> { path.moveTo(9f, 12f); path.lineTo(9.01f, 12f); path.moveTo(15f, 12f); path.lineTo(15.01f, 12f) }
        }
        val g = r * 0.9f
        c.save()
        c.translate(width / 2f - g / 2, height / 2f - g / 2)
        c.scale(g / 24f, g / 24f)
        c.drawPath(path, glyph)
        c.restore()
    }
}

/** Stroke icons for the tab bar (24-unit grid, like the desktop's). */
@SuppressLint("ViewConstructor")
class TabIcon(context: Context, private val kind: String) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    var tint = Color.GRAY
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        p.color = tint
        val s = minOf(width, height) / 24f
        c.save()
        c.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
        c.scale(s, s)
        when (kind) {
            "today" -> { c.drawCircle(12f, 12f, 8.5f, p); c.drawLine(12f, 7.5f, 12f, 12f, p); c.drawLine(12f, 12f, 15f, 14f, p) }
            "week" -> { c.drawLine(5f, 20f, 5f, 11f, p); c.drawLine(10f, 20f, 10f, 5f, p); c.drawLine(15f, 20f, 15f, 13f, p); c.drawLine(20f, 20f, 20f, 8f, p) }
            "habits" -> { c.drawLine(4f, 7f, 14f, 7f, p); c.drawLine(18f, 7f, 20f, 7f, p); c.drawLine(4f, 17f, 8f, 17f, p); c.drawLine(12f, 17f, 20f, 17f, p); c.drawCircle(16f, 7f, 2f, p); c.drawCircle(10f, 17f, 2f, p) }
            else -> { c.drawRoundRect(3f, 5f, 16f, 15f, 1.5f, 1.5f, p); c.drawLine(1.5f, 18f, 17.5f, 18f, p); c.drawRoundRect(17.5f, 9f, 22.5f, 19f, 1.2f, 1.2f, p) }
        }
        c.restore()
    }
}
