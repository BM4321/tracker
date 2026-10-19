package app.orb.tracker.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitsTest {
    private val rules = parseRules(File("../../shared/rules.default.json").readText())

    private fun session(app: String, rule: String?, cat: Category, startMin: Long, endMin: Long) =
        Session("$app-$startMin", "android-1", "android", app, app, rule, cat, startMin * 60_000, endMin * 60_000)

    @Test
    fun defaultRulesClassifyAndroidPackages() {
        assertEquals("social", classify(rules, "com.instagram.android")?.id)
        assertEquals(Category.BAD, classify(rules, "com.google.android.youtube")?.category)
        assertNull(classify(rules, "com.apple.dt.Xcode"))
    }

    @Test
    fun ruleJsonRoundTrips() {
        val r = rules.first { it.dailyLimitMin != null }
        assertEquals(r, Rule.fromJson(r.toJson()))
        assertEquals(r, Rule.fromRemote(r.toRemote()))
        val noLimit = rules.first { it.dailyLimitMin == null }
        assertEquals(noLimit, Rule.fromRemote(noLimit.toRemote()))
    }

    @Test
    fun mergeKeepsNewestPerId() {
        val remote = rules.map { if (it.id == "code") it.copy(name = "Remote", updatedAt = 10) else it } +
            Rule("new", "New", Category.GOOD, null, emptyList(), emptyList(), 5)
        val local = rules.map { if (it.id == "youtube") it.copy(name = "Local", updatedAt = 20) else it }
        val merged = mergeRules(local, remote)
        assertEquals("Remote", merged.first { it.id == "code" }.name)
        assertEquals("Local", merged.first { it.id == "youtube" }.name)
        assertEquals(rules.size + 1, merged.size)
    }

    @Test
    fun summarizeGroupsClipsAndFlagsLimits() {
        val out = summarize(listOf(
            session("com.termux", "terminal", Category.GOOD, 0, 90),
            session("com.google.android.youtube", "youtube", Category.BAD, 100, 160),
            session("com.example.x", null, Category.NEUTRAL, 230, 260),
        ), rules, 0, 240 * 60_000)
        assertEquals("terminal", out[0].key)
        assertEquals(90.0, out[0].minutes)
        assertTrue(out.first { it.key == "youtube" }.overLimit)
        assertEquals(10.0, out.first { it.key == "com.example.x" }.minutes)
    }

    @Test
    fun trackerFollowsAppsScreenAndPause() {
        val t = Tracker("android-1")
        assertEquals(OrbState.IDLE, t.update().state)
        t.onApp(rules, "com.termux", "Termux", 0)
        assertEquals(OrbState.GOOD, t.update().state)
        val out = t.onApp(rules, "com.instagram.android", "Instagram", 60_000)
        assertEquals(60_000, out[0].endedAt)
        assertEquals(OrbState.BAD, t.update().state)

        assertEquals(1, t.onIdle(120_000).size)
        assertEquals(OrbState.IDLE, t.update().state)
        assertEquals(1, t.onActive(rules, 200_000).size)
        assertEquals(OrbState.BAD, t.update().state)

        t.pause(1_000_000, 300_000)
        assertEquals(OrbState.IDLE, t.update().state)
        assertTrue(t.tick(rules, 500_000).isEmpty())
        assertEquals(1_100_000, t.tick(rules, 1_100_000)[0].startedAt)
        assertEquals(OrbState.BAD, t.update().state)
    }
}
