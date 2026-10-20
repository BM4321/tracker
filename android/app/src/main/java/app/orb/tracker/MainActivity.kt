package app.orb.tracker

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.orb.tracker.core.Category
import app.orb.tracker.core.OrbState
import app.orb.tracker.core.OrbUpdate
import app.orb.tracker.core.Rule
import app.orb.tracker.core.Session
import app.orb.tracker.core.Usage
import app.orb.tracker.core.summarize
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Sapphier: what you are doing now, your day, your week, your habits and your devices,
 * in the Orb design system's look. Everything is read from the local database, so it
 * opens instantly; a sync with your other devices runs in the background on open.
 */
class MainActivity : Activity() {
    private lateinit var t: Theme
    private lateinit var db: Db
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabBar: LinearLayout
    private var tab = "today"
    private val tabs = listOf("today" to "Today", "week" to "Week", "habits" to "Habits", "devices" to "Devices")
    private val onUpdate: (OrbUpdate) -> Unit = { runOnUiThread { if (tab == "today") render() } }
    private var showTable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = Theme.of(this)
        db = Db.get(this)
        tab = savedInstanceState?.getString("tab") ?: tab

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.ground)
            // Android 15 draws edge to edge: keep content clear of the system bars
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        tabBar = LinearLayout(this).apply {
            setBackgroundColor(t.surface)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        for ((id, label) in tabs) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                tag = id
                setOnClickListener { tab = id; scroll.scrollTo(0, 0); render() }
                contentDescription = label
                addView(TabIcon(this@MainActivity, id), LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(text(label, 10f, t.inkMuted, bold = true).apply { gravity = Gravity.CENTER })
            }
            tabBar.addView(item, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(View(this).apply { setBackgroundColor(t.line) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        root.addView(tabBar)
        setContentView(root)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        OrbService.instance?.addListener(onUpdate)
        render()
        // show the local copy at once, then catch up with the other devices
        SyncJob.now(this) { runOnUiThread { render() } }
    }

    override fun onPause() {
        OrbService.instance?.removeListener(onUpdate)
        super.onPause()
    }

    private fun render() {
        for (i in 0 until tabBar.childCount) {
            val item = tabBar.getChildAt(i) as LinearLayout
            val on = item.tag == tab
            val color = if (on) t.accent else t.inkMuted
            (item.getChildAt(0) as TabIcon).tint = color
            (item.getChildAt(1) as TextView).setTextColor(color)
            item.isSelected = on
        }
        body.removeAllViews()
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply { background = round(t.accent, 7) }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(8) })
            addView(text("Sapphier", 16f, t.ink, bold = true))
        }, spaced(14))
        if (OrbService.instance == null) body.addView(enableCard(), spaced(14))
        when (tab) {
            "today" -> today()
            "week" -> week()
            "habits" -> habits()
            else -> devices()
        }
    }

    // ---- data ------------------------------------------------------------------------

    private fun dayBounds(daysAgo: Int): Pair<Long, Long> {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -daysAgo)
        }
        val from = c.timeInMillis
        c.add(Calendar.DAY_OF_MONTH, 1)
        return from to c.timeInMillis
    }

    /** Sessions overlapping a window, the open one counted up to now. */
    private fun sessions(from: Long, to: Long): List<Session> {
        val list = db.sessionsBetween(from, to, null).toMutableList()
        OrbService.instance?.openSession?.let { open ->
            val i = list.indexOfFirst { it.id == open.id }
            if (i >= 0) list[i] = list[i].copy(endedAt = System.currentTimeMillis())
            else if (open.startedAt < to) list += open.copy(endedAt = System.currentTimeMillis())
        }
        return list
    }

    private data class Totals(val good: Double, val bad: Double, val other: Double)

    private fun totals(u: List<Usage>) = Totals(
        u.filter { it.category == Category.GOOD }.sumOf { it.minutes },
        u.filter { it.category == Category.BAD }.sumOf { it.minutes },
        u.filter { it.category == Category.NEUTRAL }.sumOf { it.minutes },
    )

    // ---- Today -----------------------------------------------------------------------

    private fun today() {
        val (from, to) = dayBounds(0)
        val list = sessions(from, to)
        val rules = db.rules()
        val usage = summarize(list, rules, from, to)
        val tt = totals(usage)
        val update = OrbService.instance?.update ?: OrbUpdate(OrbState.IDLE, null)

        body.addView(nowCard(update), spaced(14))

        val tracked = tt.good + tt.bad
        val share = if (tracked > 0) Math.round(tt.good / tracked * 100).toInt() else 0
        val over = usage.filter { it.overLimit }
        body.addView(panel("Today", "all devices").apply {
            addView(hero(tt.good, tt.bad))
            addView(BalanceBar(this@MainActivity, t, listOf(Category.GOOD to tt.good, Category.BAD to tt.bad, Category.NEUTRAL to tt.other)), LinearLayout.LayoutParams(MATCH_PARENT, dp(14)).apply { topMargin = dp(12) })
            addView(legend(listOf(Category.GOOD to "Good ${formatMinutes(tt.good)}", Category.BAD to "To reduce ${formatMinutes(tt.bad)}", Category.NEUTRAL to "Other ${formatMinutes(tt.other)}")), spaced(0).apply { topMargin = dp(8) })
            addView(text(when {
                tracked <= 0 -> "Nothing on your habits yet today."
                over.isNotEmpty() -> "$share% of your time went to good habits. ${over[0].name} is over its limit."
                share >= 70 -> "$share% of your time went to good habits. A strong day so far."
                else -> "$share% of your time went to good habits."
            }, 14f, t.ink, bold = true), spaced(0).apply { topMargin = dp(10) })
        }, spaced(14))

        // the day, one lane per device
        val first = list.minOfOrNull { it.startedAt }
        val startHour = if (first != null) ((first - from) / 3_600_000).toInt().coerceIn(0, 6) else 6
        val lanes = listOf("Mac" to "mac", "Phone" to "android")
        val segs = list.sortedBy { it.startedAt }.mapNotNull { s ->
            val lane = lanes.indexOfFirst { it.second == s.platform }.takeIf { it >= 0 } ?: return@mapNotNull null
            val rule = s.ruleId?.let { id -> rules.firstOrNull { it.id == id && !it.deleted } }
            Segment(lane, maxOf(s.startedAt, from), minOf(s.endedAt, to), rule?.category ?: s.category,
                "${s.appName}${rule?.let { if (it.name != s.appName) " · ${it.name}" else "" } ?: ""}")
        }
        val picked = text("Tap a block to see what it was.", 12f, t.inkMuted)
        body.addView(panel("Your day", null).apply {
            addView(TimelineView(this@MainActivity, t, lanes.map { it.first }, segs, from + startHour * 3_600_000L, to) { s ->
                picked.text = s?.let { "${it.label}: ${clockTime(it.start)}–${clockTime(it.end)} · ${formatMinutes((it.end - it.start) / 60000.0)}" } ?: "Tap a block to see what it was."
                picked.setTextColor(if (s != null) t.ink else t.inkMuted)
            }, spaced(0).apply { topMargin = dp(10) })
            addView(picked, spaced(0).apply { topMargin = dp(6) })
        }, spaced(14))

        // limits
        body.addView(panel("Daily limits", null).apply {
            val limited = rules.filter { !it.deleted && it.category == Category.BAD && it.dailyLimitMin != null }
            if (limited.isEmpty()) addView(text("No limits set. Add one in Habits.", 12f, t.inkMuted), spaced(0).apply { topMargin = dp(8) })
            for (r in limited) {
                val used = usage.firstOrNull { it.key == r.id }?.minutes ?: 0.0
                val limit = r.dailyLimitMin!!.toDouble()
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = round(t.surfaceRaised, 10)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    addView(LinearLayout(this@MainActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(text(r.name, 14f, t.ink, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        addView(when {
                            used > limit -> chip("Over by ${formatMinutes(used - limit)}", t.badSoft, t.bad)
                            used >= limit * 0.8 -> chip("${formatMinutes(limit - used)} left", t.neutralSoft, t.inkMuted)
                            else -> chip("${formatMinutes(limit - used)} left", t.goodSoft, t.good)
                        })
                    })
                    addView(LimitTrack(this@MainActivity, t, used, limit), spaced(0).apply { topMargin = dp(8) })
                    addView(text("${formatMinutes(used)} of ${formatMinutes(limit)}", 13f, t.inkMuted, mono = true), spaced(0).apply { topMargin = dp(6) })
                }, spaced(0).apply { topMargin = dp(10) })
            }
        }, spaced(14))

        // where it went
        body.addView(panel("Where it went", null).apply {
            if (usage.isEmpty()) addView(text("Nothing tracked yet today.", 12f, t.inkMuted), spaced(0).apply { topMargin = dp(8) })
            val max = usage.maxOfOrNull { it.minutes }?.coerceAtLeast(1.0) ?: 1.0
            for (u in usage) {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(4))
                    addView(LinearLayout(this@MainActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(initial(u.name, u.category), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(12) })
                        addView(text(u.name, 14f, t.ink, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        addView(chip(catWord(u.category), t.tint(u.category), t.text(u.category)), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(10) })
                        addView(text(formatMinutes(u.minutes), 13f, t.ink, mono = true))
                    })
                    addView(ShareBar(this@MainActivity, t, (u.minutes / max).toFloat(), u.category), LinearLayout.LayoutParams(MATCH_PARENT, dp(3)).apply { topMargin = dp(6); marginStart = dp(42) })
                })
            }
        }, spaced(14))
    }

    private fun nowCard(u: OrbUpdate): View {
        val svc = OrbService.instance
        val c = u.current
        val paused = svc?.isPaused() == true
        val open = svc?.openSession
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = round(t.surfaceRaised, 16, stroke = t.line)
            setPadding(dp(16), dp(14), dp(12), dp(14))
            addView(StateDot(this@MainActivity, t).apply { state = u.state }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(if (c != null) "NOW · ${catWord(c.category).uppercase()}" else "NOT TRACKING", 11f, t.inkMuted, bold = true).apply { letterSpacing = 0.07f })
                addView(text(c?.ruleName ?: c?.appName ?: if (paused) "Paused" else "Idle", 17f, t.ink, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(text(when {
                    c != null && open != null -> "${if (c.ruleName != null && c.ruleName != c.appName) c.appName + " · " else ""}${formatMinutes((System.currentTimeMillis() - open.startedAt) / 60000.0)} this session"
                    paused -> "Paused. Long-press the orb or tap Resume."
                    svc == null -> "Turn on Sapphier below to start tracking"
                    else -> "Time is not counting while the screen is off"
                }, 12f, t.inkMuted))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (svc != null) addView(button(if (paused) "Resume" else "Pause 30m", small = true) {
                if (paused) svc.resume() else svc.pause(30)
                render()
            })
        }
    }

    // ---- Week ------------------------------------------------------------------------

    private fun week() {
        val rules = db.rules()
        val dayName = SimpleDateFormat("EEE", Locale.getDefault())
        val days = (6 downTo 0).map { ago ->
            val (from, to) = dayBounds(ago)
            val tt = totals(summarize(sessions(from, to), rules, from, to))
            DayBar(if (ago == 0) "Today" else dayName.format(from), tt.good, tt.bad, ago == 0) to tt.other
        }
        val good = days.sumOf { it.first.good }
        val bad = days.sumOf { it.first.bad }
        val caption = text("Tap a day to see its numbers.", 12f, t.inkMuted)
        body.addView(panel("Last 7 days", null).apply {
            addView(hero(good, bad))
            addView(legend(listOf(Category.GOOD to "Good", Category.BAD to "To reduce")), spaced(0).apply { topMargin = dp(10) })
            if (showTable) {
                for ((d, other) in days) {
                    addView(LinearLayout(this@MainActivity).apply {
                        setPadding(0, dp(6), 0, dp(6))
                        addView(text(d.label, 12f, t.ink, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.2f))
                        for (v in listOf(d.good, d.bad, other)) addView(text(formatMinutes(v), 12f, t.ink, mono = true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    })
                }
            } else {
                addView(WeekChart(this@MainActivity, t, days.map { it.first }) { d ->
                    caption.text = "${d.label}: good ${formatMinutes(d.good)}, to reduce ${formatMinutes(d.bad)}"
                    caption.setTextColor(t.ink)
                }, spaced(0).apply { topMargin = dp(12) })
                addView(caption, spaced(0).apply { topMargin = dp(6) })
            }
            addView(button(if (showTable) "Show chart" else "Show table", small = true) { showTable = !showTable; render() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            val best = days.map { it.first }.filter { it.good + it.bad > 0 }.maxByOrNull { it.good - it.bad }
            addView(text(best?.let { "Your best day was ${if (it.today) "today" else it.label}: ${formatMinutes(it.good)} on good habits and ${formatMinutes(it.bad)} to reduce." }
                ?: "Your week fills in as Sapphier tracks your days.", 14f, t.ink, bold = true), spaced(0).apply { topMargin = dp(10) })
        }, spaced(14))
    }

    // ---- Habits ----------------------------------------------------------------------

    private fun habits() {
        val (from, to) = dayBounds(0)
        val rules = db.rules()
        val list = sessions(from, to)
        // apps used today that no habit covers
        val unsorted = list.filter { s -> s.ruleId == null && s.appName != "Home screen" }
            .groupBy { it.appId }
            .map { (id, ss) -> Triple(id, ss.first(), ss.sumOf { (minOf(it.endedAt, to) - maxOf(it.startedAt, from)) / 60000.0 }) }
            .filter { (id, s, _) -> rules.none { !it.deleted && (if (s.platform == "mac") it.mac else it.android).contains(id) } }
            .sortedByDescending { it.third }
        if (unsorted.isNotEmpty()) {
            body.addView(panel("Sort these apps", "${unsorted.size} not categorised").apply {
                for ((id, s, minutes) in unsorted) {
                    addView(LinearLayout(this@MainActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background = round(t.surfaceRaised, 10, stroke = t.line)
                        setPadding(dp(12), dp(10), dp(10), dp(10))
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text(s.appName, 14f, t.ink, bold = true))
                            addView(text("${formatMinutes(minutes)} today", 12f, t.inkMuted, mono = true))
                        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        addView(button("Good", small = true) { sortApp(id, s, Category.GOOD, rules) })
                        addView(button("Reduce", small = true) { sortApp(id, s, Category.BAD, rules) })
                    }, spaced(0).apply { topMargin = dp(10) })
                }
            }, spaced(14))
        }
        body.addView(panel("Your habits", "changes sync to your other devices").apply {
            for (r in rules.filter { !it.deleted }) addView(habitCard(r), spaced(0).apply { topMargin = dp(10) })
        }, spaced(14))
    }

    private fun sortApp(appId: String, s: Session, c: Category, rules: List<Rule>) {
        val id = s.appName.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val base = rules.firstOrNull { it.id == id } ?: Rule(id, s.appName, c, if (c == Category.BAD) 30 else null, emptyList(), emptyList())
        val updated = if (s.platform == "mac") base.copy(mac = (base.mac + appId).distinct()) else base.copy(android = (base.android + appId).distinct())
        save(updated.copy(category = c, deleted = false))
    }

    private fun habitCard(r: Rule): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(t.surfaceRaised, 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(text(r.name, 15f, t.ink, bold = true))
        // Good / Reduce / Neutral, one tap
        addView(LinearLayout(this@MainActivity).apply {
            background = round(t.surface, 999, stroke = t.line)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            for ((c, label) in listOf(Category.GOOD to "Good", Category.BAD to "Reduce", Category.NEUTRAL to "Neutral")) {
                val on = r.category == c
                addView(text(label, 12f, if (on) t.text(c).let { if (c == Category.NEUTRAL) t.ink else it } else t.inkMuted, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = if (on) round(t.tint(c), 999) else null
                    setPadding(0, dp(6), 0, dp(6))
                    contentDescription = "${r.name}: $label"
                    isSelected = on
                    setOnClickListener {
                        if (!on) save(r.copy(category = c, dailyLimitMin = if (c == Category.BAD && r.dailyLimitMin == null) 30 else r.dailyLimitMin))
                    }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        }, spaced(0).apply { topMargin = dp(10) })
        val apps = (r.mac + r.android).map(::appLabel).distinct()
        if (apps.isNotEmpty()) addView(text(apps.joinToString(" · "), 12f, t.inkMuted), spaced(0).apply { topMargin = dp(8) })
        val field = when (r.category) { Category.BAD -> "Daily limit"; Category.GOOD -> "Daily goal"; else -> null }
        if (field != null) {
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(field, 12f, t.inkMuted), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(stepButton("−", "Less") { save(r.copy(dailyLimitMin = ((r.dailyLimitMin ?: 0) - 15).takeIf { it > 0 })) })
                addView(text(r.dailyLimitMin?.let { formatMinutes(it.toDouble()) } ?: "None", 13f, t.ink, mono = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(72), WRAP_CONTENT))
                addView(stepButton("+", "More") { save(r.copy(dailyLimitMin = (r.dailyLimitMin ?: 0) + 15)) })
            }, spaced(0).apply { topMargin = dp(10) })
        }
    }

    private fun save(rule: Rule) {
        db.saveRule(rule.copy(updatedAt = System.currentTimeMillis()), dirty = true)
        OrbService.instance?.rulesChanged()
        render()
        SyncJob.now(this)
    }

    // ---- Devices ---------------------------------------------------------------------

    private fun devices() {
        val (from, to) = dayBounds(0)
        val (weekFrom, _) = dayBounds(6)
        val rules = db.rules()
        val week = sessions(weekFrom, to)
        val today = sessions(from, to)
        val me = db.deviceId
        val known = (week.groupBy { it.device }.mapValues { (_, ss) -> ss.first().platform to ss.maxOf { it.endedAt } } +
            (if (week.none { it.device == me }) mapOf(me to ("android" to System.currentTimeMillis())) else emptyMap()))
            .entries.sortedWith(compareBy({ it.key != me }, { -it.value.second }))
        body.addView(panel("Your devices", "today").apply {
            for ((device, info) in known) {
                val (platform, lastSeen) = info
                val tt = totals(summarize(today.filter { it.device == device }, rules, from, to))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = round(t.surfaceRaised, 16)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    addView(LinearLayout(this@MainActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(text(if (platform == "mac") "Mac" else "Android phone", 15f, t.ink, bold = true))
                        if (device == me) addView(chip("This device", t.accentSoft, t.accent), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
                    })
                    addView(text(if (device == me) "Tracking" else "Last active ${clockTime(lastSeen)}", 12f, t.inkMuted))
                    addView(LinearLayout(this@MainActivity).apply {
                        for ((v, label, color) in listOf(Triple(tt.good, "good", t.good), Triple(tt.bad, "to reduce", t.bad), Triple(tt.other, "other", t.ink))) {
                            addView(LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(text(formatMinutes(v), 18f, color, mono = true))
                                addView(text(label, 11f, t.inkMuted))
                            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(18) })
                        }
                    }, spaced(0).apply { topMargin = dp(10) })
                }, spaced(0).apply { topMargin = dp(10) })
            }
            if (known.size < 2) addView(text("Install Sapphier on your Mac and sign in with the same details below. Its day shows up here.", 12f, t.inkMuted), spaced(0).apply { topMargin = dp(10) })
        }, spaced(14))
        body.addView(syncPanel(), spaced(14))
    }

    private fun syncPanel(): View = panel("Sync", "every 30 minutes on Wi-Fi, and when you open Sapphier").apply {
        val signedIn = !db.meta("sync_refresh").isNullOrEmpty()
        val status = text("", 12f, t.inkMuted)
        fun showStatus(error: String? = null) {
            val last = db.meta("sync_last")?.toLongOrNull()
            val err = error ?: db.meta("sync_error")?.takeIf { it.isNotEmpty() }
            status.text = (if (!db.meta("sync_refresh").isNullOrEmpty()) "Signed in as ${db.meta("sync_email")}. " else "Not signed in. ") +
                (last?.let { "Last synced ${clockTime(it)}." } ?: "") + (err?.let { "\n$it" } ?: "")
            status.setTextColor(if (err != null) t.bad else t.inkMuted)
        }
        if (signedIn) {
            addView(button("Sync now", primary = true, small = true) {
                status.text = "Syncing…"
                SyncJob.now(this@MainActivity) { e -> runOnUiThread { showStatus(e) } }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        } else {
            addView(text("Your devices sync through your own free Supabase project. Create one, run sync/schema.sql in its SQL editor, add a user under Authentication, then sign in here and on your Mac with the same details.", 12f, t.inkMuted), spaced(0).apply { topMargin = dp(8) })
            val url = input(db.meta("sync_url") ?: "", "Project URL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            val key = input(db.meta("sync_key") ?: "", "Anon key", InputType.TYPE_CLASS_TEXT)
            val email = input(db.meta("sync_email") ?: "", "Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            val password = input("", "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            for (f in listOf(url, key, email, password)) addView(f, spaced(0).apply { topMargin = dp(8) })
            addView(button("Sign in", primary = true, small = true) {
                status.text = "Signing in…"
                val (u, k, e, p) = listOf(url, key, email, password).map { it.text.toString() }
                Thread {
                    val error = try { Sync.signIn(this@MainActivity, u, k, e, p); null } catch (ex: Sync.SyncError) { ex.message }
                    runOnUiThread { if (error == null) render() else { password.setText(""); showStatus(error) } }
                }.start()
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        addView(status, spaced(0).apply { topMargin = dp(8) })
        showStatus()
    }

    // ---- building blocks -------------------------------------------------------------

    private fun catWord(c: Category) = when (c) { Category.GOOD -> "Good habit"; Category.BAD -> "To reduce"; Category.NEUTRAL -> "Other" }

    private fun spaced(bottomDp: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(bottomDp) }

    /** A titled card: an uppercase label, an optional note on the right. */
    private fun panel(title: String, note: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(t.surface, 16)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title.uppercase(), 12f, t.inkMuted, bold = true).apply { letterSpacing = 0.07f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (note != null) addView(text(note, 11f, t.inkMuted).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.4f))
        })
    }

    private fun hero(good: Double, bad: Double) = LinearLayout(this).apply {
        setPadding(0, dp(10), 0, 0)
        for ((v, label, color) in listOf(Triple(good, "on good habits", t.good), Triple(bad, "on habits to reduce", t.bad))) {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(formatMinutes(v), 30f, color, mono = true))
                addView(text(label, 12f, t.inkMuted))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(24) })
        }
    }

    private fun legend(items: List<Pair<Category, String>>) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        for ((c, label) in items) {
            addView(BalanceBar(this@MainActivity, t, listOf(c to 1.0)), LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(6) })
            addView(text(label, 12f, t.inkMuted), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(14) })
        }
    }

    private fun initial(name: String, c: Category) = text(name.take(1).uppercase(), 12f, t.text(c), bold = true).apply {
        gravity = Gravity.CENTER
        background = round(t.tint(c), 8)
    }

    private fun chip(label: String, bg: Int, fg: Int) = text(label, 11f, fg, bold = true).apply {
        background = round(bg, 999)
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }

    private fun enableCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(t.accentSoft, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(text("Turn on Sapphier", 15f, t.ink, bold = true))
        addView(text("Sapphier needs its accessibility service to see which app is in front and to show the orb over other apps. It only reads app names, never what is on screen.", 12f, t.inkMuted), spaced(0).apply { topMargin = dp(4) })
        addView(text("Installed from a file? First open Settings → Apps → Sapphier → ⋮ → Allow restricted settings.", 12f, t.inkMuted), spaced(0).apply { topMargin = dp(6) })
        addView(button("Open accessibility settings", primary = true, small = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
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

    private fun button(label: String, primary: Boolean = false, small: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 13f)
        setTextColor(if (primary) t.onAccent else t.ink)
        background = if (primary) round(t.accent, 10) else round(t.ground, 10, stroke = t.line)
        setPadding(dp(12), 0, dp(12), 0)
        minHeight = dp(if (small) 36 else 40)
        minimumHeight = dp(if (small) 36 else 40)
        minWidth = 0
        minimumWidth = 0
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) }
    }

    private fun stepButton(label: String, what: String, onClick: () -> Unit) = FrameLayout(this).apply {
        val size = dp(36)
        layoutParams = LinearLayout.LayoutParams(size, size)
        background = round(t.surfaceRaised, 18, stroke = t.line)
        contentDescription = what
        addView(text(label, 16f, t.ink).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setOnClickListener { onClick() }
    }

    private fun input(value: String, hint: String, type: Int) = EditText(this).apply {
        setText(value)
        this.hint = hint
        inputType = type
        setSingleLine()
        setTextColor(t.ink)
        setHintTextColor(t.inkMuted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = round(t.surfaceRaised, 10, stroke = t.line)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }
}
