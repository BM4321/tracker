package app.orb.tracker

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import app.orb.tracker.core.Category
import app.orb.tracker.core.OrbState
import app.orb.tracker.core.OrbUpdate
import app.orb.tracker.core.Rule
import app.orb.tracker.core.Usage
import app.orb.tracker.core.summarize
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Today's breakdown, habit settings and sync, in the Orb design system's look. */
class MainActivity : Activity() {
    private lateinit var t: Theme
    private lateinit var db: Db
    private lateinit var body: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var headTitle: TextView
    private lateinit var headCaption: TextView
    private lateinit var headDot: Dot
    private var tab = "today"
    private var device: String? = null // null = all devices
    private val onUpdate: (OrbUpdate) -> Unit = { u -> runOnUiThread { showUpdate(u) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = Theme.of(this)
        db = Db.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.ground)
            // Android 15 draws edge to edge: pad around the system bars ourselves
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(dp(20) + bars.left, dp(24) + bars.top, dp(20) + bars.right, dp(24) + bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headDot = Dot(this)
        head.addView(headDot, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(16) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headTitle = text("", 18f, t.ink, bold = true)
        headCaption = text("", 11f, t.inkMuted)
        titles.addView(headTitle); titles.addView(headCaption)
        head.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(head)

        tabs = LinearLayout(this).apply { setPadding(0, dp(16), 0, dp(16)) }
        for ((id, label) in listOf("today" to "Today", "habits" to "Habits", "sync" to "Sync")) {
            tabs.addView(pill(label) { tab = id; render() }.apply { tag = id })
        }
        root.addView(tabs)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        OrbService.instance?.addListener(onUpdate)
        showUpdate(OrbService.instance?.update ?: OrbUpdate(OrbState.IDLE, null))
        render()
        SyncJob.now(this) { runOnUiThread { if (tab == "today") render() } }
    }

    override fun onPause() {
        OrbService.instance?.removeListener(onUpdate)
        super.onPause()
    }

    private fun showUpdate(u: OrbUpdate) {
        headDot.state = u.state
        val now = u.current
        headTitle.text = if (u.state == OrbState.IDLE || now == null) "Idle" else now.ruleName ?: now.appName
        headCaption.text = when (u.state) {
            OrbState.GOOD -> "Good habit: keep going"
            OrbState.BAD -> "Habit to reduce: ease off"
            OrbState.NEUTRAL -> "Not categorised yet"
            OrbState.IDLE -> if (OrbService.instance == null) "Orb is off" else "Nothing tracked right now"
        }
    }

    private fun render() {
        body.removeAllViews()
        for (i in 0 until tabs.childCount) styleSelected(tabs.getChildAt(i) as TextView, tabs.getChildAt(i).tag == tab)
        if (OrbService.instance == null) body.addView(enableCard())
        when (tab) {
            "today" -> today()
            "habits" -> habits()
            "sync" -> sync()
        }
    }

    // ---- today -----------------------------------------------------------------------

    private fun today() {
        val segs = LinearLayout(this).apply { setPadding(0, 0, 0, dp(16)) }
        for ((d, label) in listOf(null to "All devices", "mac" to "Mac", "android" to "Android")) {
            segs.addView(pill(label) { device = d; render() }.also { styleSelected(it, device == d) })
        }
        body.addView(segs)

        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val from = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val to = cal.timeInMillis
        val sessions = db.sessionsBetween(from, to, device)
        // the open session is checkpointed each minute; count it up to now
        OrbService.instance?.openSession?.let { open ->
            sessions.firstOrNull { it.id == open.id }?.endedAt = System.currentTimeMillis()
        }
        val usage = summarize(sessions, db.rules(), from, to)

        val stats = LinearLayout(this)
        fun total(c: Category) = usage.filter { it.category == c }.sumOf { it.minutes }
        for ((c, label) in listOf(Category.GOOD to "good habits", Category.BAD to "to reduce", Category.NEUTRAL to "other")) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(t.surface, 16)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(text(formatMinutes(total(c)), 22f, if (c == Category.NEUTRAL) t.ink else t.text(c), mono = true))
                addView(text(label, 11f, t.inkMuted))
            }
            stats.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        }
        body.addView(stats)

        usage.filter { it.overLimit }.takeIf { it.isNotEmpty() }?.let { over ->
            body.addView(text(over.joinToString(". ", postfix = ".") {
                "${it.name} is ${formatMinutes(it.minutes - (it.goalMin ?: 0))} over your ${formatMinutes((it.goalMin ?: 0).toDouble())} limit"
            }, 14f, t.bad).apply { setPadding(0, dp(16), 0, 0) })
        }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
        if (usage.isEmpty()) list.addView(text("Nothing tracked yet today.", 14f, t.inkMuted))
        for (u in usage) list.addView(row(u), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        body.addView(list)
    }

    /** The design system's ActivityRow. */
    private fun row(u: Usage): View {
        val goal = u.goalMin
        val note = when {
            goal == null -> null
            u.category == Category.BAD && u.minutes > goal -> "${formatMinutes(u.minutes - goal)} over ${formatMinutes(goal.toDouble())}"
            u.category == Category.BAD -> "${formatMinutes(goal - u.minutes)} left"
            else -> "goal ${formatMinutes(goal.toDouble())}"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(t.surfaceRaised, 10)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val top = LinearLayout(this)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text(u.name, 14f, t.ink, bold = true))
        left.addView(text(when (u.category) { Category.GOOD -> "Good habit"; Category.BAD -> "To reduce"; Category.NEUTRAL -> "Uncategorised" }, 11f, t.inkMuted))
        top.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(text(formatMinutes(u.minutes), 13f, if (u.category == Category.NEUTRAL) t.ink else t.text(u.category), mono = true))
        if (note != null) right.addView(text(note, 11f, if (u.category == Category.BAD && u.overLimit) t.bad else t.inkMuted))
        top.addView(right)
        box.addView(top)
        val pct = if (goal != null && goal > 0) minOf(u.minutes / goal, 1.0).toFloat() else 1f
        box.addView(Meter(this, pct, t.line, if (u.category == Category.NEUTRAL) t.inkMuted else t.text(u.category)),
            LinearLayout.LayoutParams(MATCH_PARENT, dp(4)).apply { topMargin = dp(8) })
        return box
    }

    // ---- habits ----------------------------------------------------------------------

    private fun habits() {
        body.addView(text("Choose what counts as good and what you want to reduce. Changes sync to your other devices.", 14f, t.inkMuted)
            .apply { setPadding(0, 0, 0, dp(16)) })
        val cats = listOf(Category.GOOD, Category.BAD, Category.NEUTRAL)
        for (r in db.rules().filter { !it.deleted }) {
            val box = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = round(t.surfaceRaised, 10)
                setPadding(dp(16), dp(8), dp(8), dp(8))
            }
            box.addView(text(r.name, 13f, t.ink, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Good", "Reduce", "Neutral"))
                setSelection(cats.indexOf(r.category))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (cats[pos] != r.category) save(r.copy(category = cats[pos]))
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) = Unit
                }
            }
            box.addView(spinner)
            val limit = input(r.dailyLimitMin?.toString() ?: "", "min/day", InputType.TYPE_CLASS_NUMBER).apply {
                setOnFocusChangeListener { v, focused ->
                    if (!focused) {
                        val n = (v as EditText).text.toString().toIntOrNull()
                        if (n != r.dailyLimitMin) save(r.copy(dailyLimitMin = n))
                    }
                }
            }
            box.addView(limit, LinearLayout.LayoutParams(dp(88), WRAP_CONTENT))
            body.addView(box, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun save(rule: Rule) {
        db.saveRule(rule.copy(updatedAt = System.currentTimeMillis()), dirty = true)
        OrbService.instance?.rulesChanged()
        SyncJob.now(this)
    }

    // ---- sync ------------------------------------------------------------------------

    private fun sync() {
        body.addView(text("Mac and Android sync through your own free Supabase project. Create one, run sync/schema.sql in its SQL editor, " +
            "add a user under Authentication, then sign in with the same details on both devices.", 14f, t.inkMuted)
            .apply { setPadding(0, 0, 0, dp(16)) })
        val url = input(db.meta("sync_url") ?: "", "Project URL", InputType.TYPE_TEXT_VARIATION_URI)
        val key = input(db.meta("sync_key") ?: "", "Anon key", InputType.TYPE_CLASS_TEXT)
        val email = input(db.meta("sync_email") ?: "", "Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = input("", "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        for (f in listOf(url, key, email, password)) body.addView(f, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        val status = text("", 11f, t.inkMuted)
        fun showStatus(error: String? = null) {
            val signedIn = !db.meta("sync_refresh").isNullOrEmpty()
            val last = db.meta("sync_last")?.toLongOrNull()
            val err = error ?: db.meta("sync_error")?.takeIf { it.isNotEmpty() }
            status.text = (if (signedIn) "Signed in as ${db.meta("sync_email")}. " else "Not signed in. ") +
                (last?.let { "Last synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}." } ?: "") +
                (err?.let { "\n$it" } ?: "")
            status.setTextColor(if (err != null) t.bad else t.inkMuted)
        }
        val buttons = LinearLayout(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        buttons.addView(button("Sign in", primary = true) {
            status.text = "Signing in…"
            val (u, k, e, p) = listOf(url, key, email, password).map { it.text.toString() }
            Thread {
                val error = try { Sync.signIn(this, u, k, e, p); null } catch (ex: Sync.SyncError) { ex.message }
                runOnUiThread { password.setText(""); showStatus(error) }
            }.start()
        })
        buttons.addView(button("Sync now") {
            status.text = "Syncing…"
            SyncJob.now(this) { e -> runOnUiThread { showStatus(e) } }
        })
        body.addView(buttons)
        body.addView(status)
        showStatus()
    }

    // ---- building blocks -------------------------------------------------------------

    private fun enableCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(t.badSoft, 16)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(text("Turn on Orb", 14f, t.ink, bold = true))
        addView(text("Orb needs its accessibility service to see which app is in front and to float over other apps. " +
            "It only reads app names, never what is on screen.", 12f, t.inkMuted))
        addView(button("Open accessibility settings", primary = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun round(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toFloat())
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun pill(label: String, onClick: () -> Unit) = text(label, 12f, t.inkMuted, bold = true).apply {
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    private fun styleSelected(v: TextView, on: Boolean) {
        v.background = if (on) round(t.surfaceRaised, 999) else null
        v.setTextColor(if (on) t.ink else t.inkMuted)
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(if (primary) t.ground else t.ink)
        background = if (primary) round(t.ink, 10) else round(t.ground, 10, stroke = t.line)
        setPadding(dp(16), 0, dp(16), 0)
        minHeight = dp(40)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
    }

    private fun input(value: String, hint: String, type: Int) = EditText(this).apply {
        setText(value)
        this.hint = hint
        inputType = type
        setSingleLine()
        setTextColor(t.ink)
        setHintTextColor(t.inkMuted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        background = round(t.surface, 10, stroke = t.line)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    /** The orb's colour in the header. */
    private class Dot(activity: MainActivity) : View(activity) {
        private val theme = activity.t
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var state = OrbState.IDLE
            set(v) { field = v; invalidate() }

        override fun onDraw(canvas: Canvas) {
            paint.color = theme.orbFill(state)
            paint.alpha = if (state == OrbState.IDLE) 153 else 255
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) / 2f, paint)
        }
    }

    /** The ActivityRow meter: a track with a fill. */
    private class Meter(activity: MainActivity, private val pct: Float, private val track: Int, private val fill: Int) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = track
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            paint.color = fill
            canvas.drawRoundRect(0f, 0f, width * pct, height.toFloat(), r, r, paint)
        }
    }
}
