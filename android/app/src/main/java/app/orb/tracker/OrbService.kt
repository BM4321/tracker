package app.orb.tracker

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowInsets
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
 * the CPU only wakes on app switches, screen on/off and a 5-minute save of the open session
 * while the screen is on. The orb is a TYPE_ACCESSIBILITY_OVERLAY window, which needs no
 * "draw over other apps" permission.
 */
class OrbService : AccessibilityService() {

    companion object {
        /** The running service, for the activity and the sync job. */
        @Volatile var instance: OrbService? = null
            private set

        /** The open session is saved this often while the screen is on, and on every switch. */
        private const val CHECKPOINT_MS = 5 * 60_000L
        /** The orb's diameter. */
        private const val ORB_DP = 48
    }

    private lateinit var db: Db
    private lateinit var tracker: Tracker
    @Volatile private var rules: List<Rule> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var orb: WallOrb? = null
    private var params: WindowManager.LayoutParams? = null
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
    // The orb's window is a small square around it at rest (so it blocks almost no touches)
    // and the whole usable screen while you hold or drag it, so it can flow to any edge.

    private val orbRadius get() = dp(ORB_DP / 2f)

    /** The usable screen: inside the status bar, navigation bar and any display cutout. */
    @Suppress("DEPRECATION")
    private fun usableBounds(): RectF {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = wm.currentWindowMetrics
            val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val b = m.bounds
            return RectF((b.left + i.left).toFloat(), (b.top + i.top).toFloat(), (b.right - i.right).toFloat(), (b.bottom - i.bottom).toFloat())
        }
        val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        fun dimen(name: String) = resources.getIdentifier(name, "dimen", "android").let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
        return RectF(0f, dimen("status_bar_height").toFloat(), dm.widthPixels.toFloat(), (dm.heightPixels - dimen("navigation_bar_height")).toFloat())
    }

    /** The saved anchor ("fx,fy,nx,ny", fractions of the usable screen), placed on `b`. */
    private fun savedAnchor(b: RectF): Anchor {
        val parts = db.meta("orb_anchor")?.split(",")?.mapNotNull { it.toFloatOrNull() }
        val (fx, fy, nx, ny) = if (parts?.size == 4) parts else listOf(1f, 0.35f, -1f, 0f)
        val r = orbRadius
        val x = when { nx > 0 -> b.left; nx < 0 -> b.right; else -> (b.left + fx * b.width()).coerceIn(b.left + r, b.right - r) }
        val y = when { ny > 0 -> b.top; ny < 0 -> b.bottom; else -> (b.top + fy * b.height()).coerceIn(b.top + r, b.bottom - r) }
        return Anchor(x, y, nx.toInt(), ny.toInt())
    }

    private fun restFrame(a: Anchor, b: RectF): Rect {
        val half = orbRadius * 1.5f
        return Rect(
            maxOf(b.left, a.x - half).toInt(), maxOf(b.top, a.y - half).toInt(),
            minOf(b.right, a.x + half).toInt(), minOf(b.bottom, a.y + half).toInt(),
        )
    }

    private fun place(frame: Rect) {
        val p = params ?: return
        val view = orb ?: return
        if (p.x == frame.left && p.y == frame.top && p.width == frame.width() && p.height == frame.height()) return
        p.x = frame.left
        p.y = frame.top
        p.width = frame.width()
        p.height = frame.height()
        view.setOrigin(frame.left.toFloat(), frame.top.toFloat())
        wm.updateViewLayout(view, p)
    }

    private fun showOrb() {
        val b = usableBounds()
        val view = WallOrb(this, orbRadius, object : WallOrb.Listener {
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

            override fun onPressStart() {
                val full = orb?.bounds ?: return
                place(Rect(full.left.toInt(), full.top.toInt(), full.right.toInt(), full.bottom.toInt()))
            }

            override fun onMove(anchor: Anchor) {
                val u = orb?.bounds ?: return
                db.setMeta("orb_anchor", listOf((anchor.x - u.left) / u.width(), (anchor.y - u.top) / u.height(), anchor.nx, anchor.ny).joinToString(","))
            }

            override fun onSettled() {
                val o = orb ?: return
                place(restFrame(o.anchor, o.bounds))
            }
        })
        view.bounds = b
        view.anchor = savedAnchor(b)
        view.state = tracker.update().state
        val frame = restFrame(view.anchor, b)
        view.setOrigin(frame.left.toFloat(), frame.top.toFloat())
        val p = WindowManager.LayoutParams(
            frame.width(), frame.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = frame.left
            y = frame.top
        }
        wm.addView(view, p)
        orb = view
        params = p
    }

    /** Rotation or a new screen size: put the orb back on the same wall, in proportion. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val o = orb ?: return
        val b = usableBounds()
        o.bounds = b
        o.anchor = savedAnchor(b)
        place(restFrame(o.anchor, b))
    }
}
