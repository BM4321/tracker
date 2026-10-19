package app.orb.tracker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import app.orb.tracker.core.OrbUpdate
import app.orb.tracker.core.Rule
import app.orb.tracker.core.Session
import app.orb.tracker.core.Tracker

/**
 * Watches which app is in front and floats the orb over everything.
 *
 * An accessibility service is told when the foreground window changes, so nothing polls:
 * the CPU only wakes on app switches, screen on/off and a once-a-minute checkpoint while
 * the screen is on. The orb is a TYPE_ACCESSIBILITY_OVERLAY window, which needs no
 * "draw over other apps" permission.
 */
class OrbService : AccessibilityService() {

    companion object {
        /** The running service, for the activity and the sync job. */
        @Volatile var instance: OrbService? = null
            private set

        private const val CHECKPOINT_MS = 60_000L
        private const val ORB_DP = 36
        // the orb window at rest (orb, tether and droplets) and while handled
        private const val REST_W_DP = 72
        private const val REST_H_DP = 64
        private const val HANDLED_DP = 260
    }

    private lateinit var db: Db
    private lateinit var tracker: Tracker
    @Volatile private var rules: List<Rule> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var orb: LiquidOrb? = null
    private var params: WindowManager.LayoutParams? = null
    private var orbCy = 0 // the orb's vertical centre on screen, px
    private val launchable = HashMap<String, Boolean>()
    private val listeners = mutableSetOf<(OrbUpdate) -> Unit>()

    val update: OrbUpdate get() = tracker.update()
    val openSession: Session? get() = tracker.openSession

    fun addListener(l: (OrbUpdate) -> Unit) { listeners += l }
    fun removeListener(l: (OrbUpdate) -> Unit) { listeners -= l }

    private val checkpoint = object : Runnable {
        override fun run() {
            apply(tracker.tick(rules, System.currentTimeMillis()))
            main.postDelayed(this, CHECKPOINT_MS)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    apply(tracker.onIdle(now))
                    main.removeCallbacks(checkpoint) // nothing runs while the screen is off
                }
                Intent.ACTION_USER_PRESENT -> {
                    apply(tracker.onActive(rules, now))
                    main.removeCallbacks(checkpoint)
                    main.postDelayed(checkpoint, CHECKPOINT_MS)
                }
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        db = Db.get(this)
        tracker = Tracker(db.deviceId)
        rules = db.rules()
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        showOrb()
        main.postDelayed(checkpoint, CHECKPOINT_MS)
        SyncJob.schedule(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || !isApp(pkg)) return // keyboards, system UI, our own window
        val label = if (pkg in homePackages) "Home screen" else try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        apply(tracker.onApp(rules, pkg, label, System.currentTimeMillis()))
    }

    /** Launchers: going home ends the previous app's time (it counts as "Home screen"). */
    private val homePackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }

    /** Apps with a launcher icon, and the home screen, count; keyboards and system overlays don't. */
    private fun isApp(pkg: String): Boolean =
        pkg in homePackages || launchable.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        apply(tracker.onIdle(System.currentTimeMillis()))
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(screen)
        orb?.let { wm.removeView(it) }
        instance = null
        super.onDestroy()
    }

    private fun apply(sessions: List<Session>) {
        for (s in sessions) db.upsertSession(s, dirty = true)
        val u = tracker.update()
        orb?.state = u.state
        listeners.toList().forEach { it(u) }
    }

    /** Rules edited here or synced from another device: re-colour what is in front. */
    fun rulesChanged() {
        main.post {
            rules = db.rules()
            apply(tracker.reclassify(rules, System.currentTimeMillis()))
        }
    }

    fun pause(minutes: Int) {
        val now = System.currentTimeMillis()
        apply(tracker.pause(now + minutes * 60_000L, now))
    }

    fun resume() = apply(tracker.resume(rules, System.currentTimeMillis()))

    fun isPaused() = tracker.isPaused(System.currentTimeMillis())

    // ---- the orb window --------------------------------------------------------------

    private fun showOrb() {
        val screenH = resources.displayMetrics.heightPixels
        orbCy = db.meta("orb_cy")?.toIntOrNull() ?: (screenH * 0.4).toInt()
        val p = WindowManager.LayoutParams(
            dp(REST_W_DP), dp(REST_H_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = orbCy - dp(REST_H_DP) / 2
        }
        val view = LiquidOrb(this, dp(ORB_DP.toFloat()), object : LiquidOrb.Listener {
            override fun onClick() {
                startActivity(Intent(this@OrbService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            override fun onLongPress() {
                if (isPaused()) {
                    resume()
                    Toast.makeText(this@OrbService, "Tracking resumed", Toast.LENGTH_SHORT).show()
                } else {
                    pause(30)
                    Toast.makeText(this@OrbService, "Paused for 30 minutes. Long-press to resume.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onPressStart() = resize(dp(HANDLED_DP), dp(HANDLED_DP))
            override fun onSettled() = resize(dp(REST_W_DP), dp(REST_H_DP))

            override fun onDragEnd(dx: Float, dy: Float): Float {
                // a vertical pull slides the orb along the edge; sideways pulls spring back
                if (kotlin.math.abs(dy) < dp(ORB_DP) * 0.6f) return 0f
                val half = dp(REST_H_DP) / 2
                val next = (orbCy + dy.toInt()).coerceIn(half, screenH - half)
                val moved = next - orbCy
                orbCy = next
                db.setMeta("orb_cy", orbCy.toString())
                params?.let { resize(it.width, it.height) }
                return moved.toFloat()
            }
        })
        view.state = tracker.update().state
        wm.addView(view, p)
        orb = view
        params = p
    }

    /** Resizes the orb window around the orb, keeping it on the right edge at orbCy. */
    private fun resize(w: Int, h: Int) {
        val p = params ?: return
        val view = orb ?: return
        p.width = w
        p.height = h
        p.y = orbCy - h / 2
        wm.updateViewLayout(view, p)
    }
}
