package app.orb.tracker

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.orb.tracker.core.Category
import app.orb.tracker.core.Rule
import app.orb.tracker.core.Session
import app.orb.tracker.core.parseRules
import org.json.JSONObject
import java.util.UUID

/**
 * Local storage, the same tables as the desktop app (desktop/src-tauri/src/store.rs).
 * Rows changed on this device stay `dirty` until the sync pushes them.
 */
class Db private constructor(private val context: Context) :
    SQLiteOpenHelper(context, "orb.sqlite", null, 1) {

    companion object {
        @Volatile private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(context.applicationContext).also { instance = it } }

        private const val SESSION_COLS = "id, device, platform, app_id, app_name, rule_id, category, started_at, ended_at"
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions (
                 id TEXT PRIMARY KEY, device TEXT NOT NULL, platform TEXT NOT NULL,
                 app_id TEXT NOT NULL, app_name TEXT NOT NULL, rule_id TEXT, category TEXT NOT NULL,
                 started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 1)""",
        )
        db.execSQL("CREATE INDEX sessions_started ON sessions (started_at)")
        db.execSQL("CREATE TABLE rules (id TEXT PRIMARY KEY, json TEXT NOT NULL, updated_at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        val defaults = context.assets.open("rules.default.json").bufferedReader().use { it.readText() }
        for (r in parseRules(defaults)) saveRule(db, r, dirty = false)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- meta ------------------------------------------------------------------------

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null,
            ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** A stable id for this install, used to tell devices apart when syncing. */
    val deviceId: String by lazy {
        meta("device_id") ?: "android-${UUID.randomUUID()}".also { setMeta("device_id", it) }
    }

    // ---- rules -----------------------------------------------------------------------

    fun rules(): List<Rule> =
        readableDatabase.rawQuery("SELECT json FROM rules ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(Rule.fromJson(JSONObject(c.getString(0)))) }
        }

    fun dirtyRules(): List<Rule> =
        readableDatabase.rawQuery("SELECT json FROM rules WHERE dirty = 1", null).use { c ->
            buildList { while (c.moveToNext()) add(Rule.fromJson(JSONObject(c.getString(0)))) }
        }

    fun saveRule(rule: Rule, dirty: Boolean) = saveRule(writableDatabase, rule, dirty)

    private fun saveRule(db: SQLiteDatabase, rule: Rule, dirty: Boolean) {
        db.insertWithOnConflict("rules", null, ContentValues().apply {
            put("id", rule.id)
            put("json", rule.toJson().toString())
            put("updated_at", rule.updatedAt)
            put("dirty", if (dirty) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Clears `dirty` only if the rule was not edited again while the push was in flight. */
    fun markRuleClean(id: String, updatedAt: Long) {
        writableDatabase.execSQL("UPDATE rules SET dirty = 0 WHERE id = ? AND updated_at = ?", arrayOf<Any>(id, updatedAt))
    }

    // ---- sessions --------------------------------------------------------------------

    fun upsertSession(s: Session, dirty: Boolean) {
        writableDatabase.execSQL(
            """INSERT INTO sessions ($SESSION_COLS, dirty) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (id) DO UPDATE SET rule_id = excluded.rule_id, category = excluded.category,
                 ended_at = excluded.ended_at, dirty = excluded.dirty""",
            arrayOf<Any?>(s.id, s.device, s.platform, s.appId, s.appName, s.ruleId, s.category.key,
                s.startedAt, s.endedAt, if (dirty) 1 else 0),
        )
    }

    private fun Cursor.toSession() = Session(
        id = getString(0), device = getString(1), platform = getString(2), appId = getString(3),
        appName = getString(4), ruleId = if (isNull(5)) null else getString(5),
        category = Category.of(getString(6)), startedAt = getLong(7), endedAt = getLong(8),
    )

    /** Sessions overlapping [from, to), optionally from one platform. */
    fun sessionsBetween(from: Long, to: Long, platform: String?): List<Session> {
        val sql = "SELECT $SESSION_COLS FROM sessions WHERE started_at < ? AND ended_at > ?" +
            if (platform != null) " AND platform = ?" else ""
        val args = listOfNotNull(to.toString(), from.toString(), platform).toTypedArray()
        return readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toSession()) } }
    }

    fun dirtySessions(limit: Int): List<Session> =
        readableDatabase.rawQuery("SELECT $SESSION_COLS FROM sessions WHERE dirty = 1 ORDER BY started_at LIMIT $limit", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toSession()) } }

    /** Clears `dirty` only if the session did not grow while the push was in flight. */
    fun markSessionClean(id: String, endedAt: Long) {
        writableDatabase.execSQL("UPDATE sessions SET dirty = 0 WHERE id = ? AND ended_at = ?", arrayOf<Any>(id, endedAt))
    }
}
