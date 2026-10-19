package app.orb.tracker.core

import java.util.UUID

data class Current(
    val appId: String,
    val appName: String,
    val ruleId: String?,
    val ruleName: String?,
    val category: Category,
)

data class OrbUpdate(val state: OrbState, val current: Current?)

/**
 * Turns foreground-app changes, screen on/off and pause into sessions and the orb's state.
 * The Android mirror of desktop/src-tauri/src/tracker.rs. Methods return sessions to save.
 */
class Tracker(private val device: String) {
    private var open: Session? = null
    private var openRuleName: String? = null
    private var front: Pair<String, String>? = null
    private var idle = false
    private var pausedUntil: Long? = null

    val openSession: Session? get() = open

    fun update(): OrbUpdate {
        val s = open ?: return OrbUpdate(OrbState.IDLE, null)
        return OrbUpdate(OrbState.of(s.category), Current(s.appId, s.appName, s.ruleId, openRuleName, s.category))
    }

    fun isPaused(now: Long): Boolean = pausedUntil?.let { now < it } ?: false

    private fun tracking(now: Long) = !idle && !isPaused(now)

    private fun close(at: Long): List<Session> {
        val s = open ?: return emptyList()
        open = null
        s.endedAt = maxOf(at, s.startedAt)
        return listOf(s)
    }

    private fun start(rules: List<Rule>, now: Long) {
        val (appId, appName) = front ?: return
        val rule = classify(rules, appId)
        open = Session(UUID.randomUUID().toString(), device, "android", appId, appName, rule?.id,
            rule?.category ?: Category.NEUTRAL, now, now)
        openRuleName = rule?.name
    }

    private fun openCopy(): List<Session> = listOfNotNull(open?.copy())

    fun onApp(rules: List<Rule>, appId: String, appName: String, now: Long): List<Session> {
        if (open?.appId == appId) return emptyList()
        front = appId to appName
        val out = close(now).toMutableList()
        if (tracking(now)) {
            start(rules, now)
            out += openCopy()
        }
        return out
    }

    /** Screen off: time stops counting. */
    fun onIdle(since: Long): List<Session> {
        if (idle) return emptyList()
        idle = true
        return close(since)
    }

    /** Screen on and unlocked: resume with what is in front. */
    fun onActive(rules: List<Rule>, now: Long): List<Session> {
        if (!idle) return emptyList()
        idle = false
        if (tracking(now)) start(rules, now)
        return openCopy()
    }

    fun pause(until: Long, now: Long): List<Session> {
        pausedUntil = until
        return close(now)
    }

    fun resume(rules: List<Rule>, now: Long): List<Session> {
        pausedUntil = null
        if (open == null && !idle) start(rules, now)
        return openCopy()
    }

    /** Minute checkpoint: extends the open session, ends an expired pause. */
    fun tick(rules: List<Rule>, now: Long): List<Session> {
        if (pausedUntil != null && !isPaused(now)) return resume(rules, now)
        val s = open ?: return emptyList()
        s.endedAt = now
        return listOf(s.copy())
    }

    /** Rules changed: split the open session so earlier time keeps its category. */
    fun reclassify(rules: List<Rule>, now: Long): List<Session> {
        val s = open ?: return emptyList()
        val rule = classify(rules, s.appId)
        if (rule?.id == s.ruleId && (rule?.category ?: Category.NEUTRAL) == s.category) {
            openRuleName = rule?.name
            return emptyList()
        }
        val out = close(now).toMutableList()
        start(rules, now)
        out += openCopy()
        return out
    }
}
