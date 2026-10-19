package app.orb.tracker

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.orb.tracker.core.Rule
import app.orb.tracker.core.Session
import app.orb.tracker.core.mergeRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Sync with the user's Supabase project (sync/schema.sql), the same protocol as
 * desktop/src-tauri/src/sync.rs: push this device's sessions, pull everyone else's,
 * merge rules last-writer-wins. Blocking: call from a background thread.
 */
object Sync {
    private const val PAGE = 1000

    class SyncError(message: String) : Exception(message)

    private fun http(method: String, url: String, key: String, bearer: String?, body: String? = null, prefer: String? = null): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Content-Type", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (prefer != null) conn.setRequestProperty("Prefer", prefer)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw SyncError("Sync failed ($code): $text")
            return text
        } catch (e: IOException) {
            throw SyncError("Could not reach Supabase: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun token(db: Db, url: String, key: String, grant: String, body: JSONObject): String {
        val res = try {
            JSONObject(http("POST", "$url/auth/v1/token?grant_type=$grant", key, null, body.toString()))
        } catch (e: SyncError) {
            throw SyncError("Sign-in failed: ${e.message}")
        }
        db.setMeta("sync_refresh", res.getString("refresh_token"))
        return res.getString("access_token")
    }

    fun signIn(context: Context, url: String, key: String, email: String, password: String) {
        val db = Db.get(context)
        val u = url.trim().trimEnd('/')
        db.setMeta("sync_url", u)
        db.setMeta("sync_key", key.trim())
        db.setMeta("sync_email", email.trim())
        token(db, u, key.trim(), "password", JSONObject().put("email", email.trim()).put("password", password))
        run(context)
    }

    @Synchronized
    fun run(context: Context) {
        val db = Db.get(context)
        try {
            runOnce(context, db)
            db.setMeta("sync_last", System.currentTimeMillis().toString())
            db.setMeta("sync_error", "")
        } catch (e: SyncError) {
            db.setMeta("sync_error", e.message ?: "Sync failed")
            throw e
        }
    }

    private fun runOnce(context: Context, db: Db) {
        val url = db.meta("sync_url").orEmpty()
        val key = db.meta("sync_key").orEmpty()
        val refresh = db.meta("sync_refresh").orEmpty()
        if (url.isEmpty() || refresh.isEmpty()) return // not set up
        val access = try {
            token(db, url, key, "refresh_token", JSONObject().put("refresh_token", refresh))
        } catch (e: SyncError) {
            db.setMeta("sync_refresh", "")
            throw SyncError("${e.message} Sign in again.")
        }
        val upsert = "resolution=merge-duplicates,return=minimal"

        // 1. push this device's sessions
        while (true) {
            val batch = db.dirtySessions(PAGE)
            if (batch.isEmpty()) break
            http("POST", "$url/rest/v1/sessions?on_conflict=id", key, access,
                JSONArray(batch.map(Session::toRemote)).toString(), upsert)
            batch.forEach { db.markSessionClean(it.id, it.endedAt) }
            if (batch.size < PAGE) break
        }

        // 2. pull everyone else's sessions since the cursor (server time)
        var cursor = db.meta("sync_cursor") ?: "1970-01-01T00:00:00Z"
        while (true) {
            val q = "select=id,device,platform,app_id,app_name,rule_id,category,started_at,ended_at,updated_at" +
                "&device=neq.${enc(db.deviceId)}&updated_at=gt.${enc(cursor)}&order=updated_at.asc&limit=$PAGE"
            val rows = JSONArray(http("GET", "$url/rest/v1/sessions?$q", key, access))
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                db.upsertSession(Session.fromRemote(o), dirty = false)
                cursor = o.getString("updated_at")
            }
            db.setMeta("sync_cursor", cursor)
            if (rows.length() < PAGE) break
        }

        // 3. rules: push local edits, then pull and merge
        val dirty = db.dirtyRules()
        if (dirty.isNotEmpty()) {
            http("POST", "$url/rest/v1/rules?on_conflict=user_id,id", key, access,
                JSONArray(dirty.map(Rule::toRemote)).toString(), upsert)
            dirty.forEach { db.markRuleClean(it.id, it.updatedAt) }
        }
        val remote = JSONArray(http("GET", "$url/rest/v1/rules?select=id,name,category,daily_limit_min,mac,android,deleted,updated_at", key, access))
        if (remote.length() > 0) {
            val local = db.rules()
            val merged = mergeRules(local, (0 until remote.length()).map { Rule.fromRemote(remote.getJSONObject(it)) })
            merged.filter { it !in local }.forEach { db.saveRule(it, dirty = false) }
            OrbService.instance?.rulesChanged()
        }
    }
}

/** Runs [Sync] every 15 minutes when there is a network (the shortest period Android allows). */
class SyncJob : JobService() {
    companion object {
        private const val ID = 1

        fun schedule(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.getPendingJob(ID) != null) return
            js.schedule(JobInfo.Builder(ID, ComponentName(context, SyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build())
        }

        /** A one-off sync on a background thread (window opened, "Sync now"). */
        fun now(context: Context, done: (String?) -> Unit = {}) {
            Thread {
                val error = try { Sync.run(context); null } catch (e: Sync.SyncError) { e.message }
                done(error)
            }.start()
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val retry = try { Sync.run(this); false } catch (e: Sync.SyncError) { true }
            jobFinished(params, retry)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
