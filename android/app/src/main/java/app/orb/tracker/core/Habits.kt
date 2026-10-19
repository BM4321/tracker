package app.orb.tracker.core

import org.json.JSONArray
import org.json.JSONObject

// The Android mirror of core/src/lib.rs: habit rules, sessions and the daily summary.
// Keep the two in step; both read shared/rules.default.json.

enum class Category {
    GOOD, BAD, NEUTRAL;

    val key: String get() = name.lowercase()

    companion object {
        fun of(key: String): Category = entries.firstOrNull { it.key == key } ?: NEUTRAL
    }
}

enum class OrbState {
    GOOD, BAD, NEUTRAL, IDLE;

    companion object {
        fun of(c: Category): OrbState = when (c) {
            Category.GOOD -> GOOD
            Category.BAD -> BAD
            Category.NEUTRAL -> NEUTRAL
        }
    }
}

/** A habit: a named group of apps. Matchers ending in `*` match a prefix. */
data class Rule(
    val id: String,
    val name: String,
    val category: Category,
    val dailyLimitMin: Int?,
    val mac: List<String>,
    val android: List<String>,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
) {
    fun matches(pkg: String): Boolean = android.any { m ->
        if (m.endsWith("*")) pkg.startsWith(m.dropLast(1)) else m == pkg
    }

    /** The shape used in rules.default.json and the local database. */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("category", category.key)
        .put("dailyLimitMin", dailyLimitMin ?: JSONObject.NULL)
        .put("mac", JSONArray(mac)).put("android", JSONArray(android))
        .put("updatedAt", updatedAt).put("deleted", deleted)

    /** The shape of the Supabase `rules` table. */
    fun toRemote(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("category", category.key)
        .put("daily_limit_min", dailyLimitMin ?: JSONObject.NULL)
        .put("mac", JSONArray(mac)).put("android", JSONArray(android))
        .put("updated_at", updatedAt).put("deleted", deleted)

    companion object {
        private fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

        private fun limit(o: JSONObject, key: String): Int? = if (o.isNull(key)) null else o.optInt(key)

        fun fromJson(o: JSONObject) = Rule(
            id = o.getString("id"),
            name = o.getString("name"),
            category = Category.of(o.getString("category")),
            dailyLimitMin = limit(o, "dailyLimitMin"),
            mac = strings(o.optJSONArray("mac")),
            android = strings(o.optJSONArray("android")),
            updatedAt = o.optLong("updatedAt", 0),
            deleted = o.optBoolean("deleted", false),
        )

        fun fromRemote(o: JSONObject) = Rule(
            id = o.getString("id"),
            name = o.getString("name"),
            category = Category.of(o.getString("category")),
            dailyLimitMin = limit(o, "daily_limit_min"),
            mac = strings(o.optJSONArray("mac")),
            android = strings(o.optJSONArray("android")),
            updatedAt = o.optLong("updated_at", 0),
            deleted = o.optBoolean("deleted", false),
        )
    }
}

fun parseRules(json: String): List<Rule> {
    val arr = JSONObject(json).getJSONArray("rules")
    return (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }
}

fun classify(rules: List<Rule>, pkg: String): Rule? = rules.firstOrNull { !it.deleted && it.matches(pkg) }

/** Per id, the newer `updatedAt` wins. */
fun mergeRules(local: List<Rule>, remote: List<Rule>): List<Rule> {
    val out = local.toMutableList()
    for (r in remote) {
        val i = out.indexOfFirst { it.id == r.id }
        when {
            i < 0 -> out.add(r)
            r.updatedAt > out[i].updatedAt -> out[i] = r
        }
    }
    return out
}

/** One stretch of time in one app on one device. Times are Unix ms. */
data class Session(
    val id: String,
    val device: String,
    val platform: String,
    val appId: String,
    val appName: String,
    val ruleId: String?,
    val category: Category,
    val startedAt: Long,
    var endedAt: Long,
) {
    fun toRemote(): JSONObject = JSONObject()
        .put("id", id).put("device", device).put("platform", platform)
        .put("app_id", appId).put("app_name", appName).put("rule_id", ruleId ?: JSONObject.NULL)
        .put("category", category.key).put("started_at", startedAt).put("ended_at", endedAt)

    companion object {
        fun fromRemote(o: JSONObject) = Session(
            id = o.getString("id"),
            device = o.getString("device"),
            platform = o.getString("platform"),
            appId = o.getString("app_id"),
            appName = o.getString("app_name"),
            ruleId = if (o.isNull("rule_id")) null else o.getString("rule_id"),
            category = Category.of(o.getString("category")),
            startedAt = o.getLong("started_at"),
            endedAt = o.getLong("ended_at"),
        )
    }
}

data class Usage(
    val key: String,
    val name: String,
    val category: Category,
    val minutes: Double,
    val goalMin: Int?,
    val overLimit: Boolean,
)

/** Totals sessions overlapping [from, to) per habit, longest first, clipping to the window. */
fun summarize(sessions: List<Session>, rules: List<Rule>, from: Long, to: Long): List<Usage> {
    val acc = LinkedHashMap<String, Usage>()
    for (s in sessions) {
        val start = maxOf(s.startedAt, from)
        val end = minOf(s.endedAt, to)
        if (end <= start) continue
        val minutes = (end - start) / 60_000.0
        val rule = s.ruleId?.let { id -> rules.firstOrNull { it.id == id && !it.deleted } }
        val key = rule?.id ?: s.appId
        val prev = acc[key]
        acc[key] = prev?.copy(minutes = prev.minutes + minutes)
            ?: Usage(key, rule?.name ?: s.appName, rule?.category ?: s.category, minutes, rule?.dailyLimitMin, false)
    }
    return acc.values
        .map { u -> u.copy(overLimit = u.category == Category.BAD && u.goalMin != null && u.minutes > u.goalMin) }
        .sortedByDescending { it.minutes }
}
