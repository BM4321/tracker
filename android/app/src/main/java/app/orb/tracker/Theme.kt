package app.orb.tracker

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import app.orb.tracker.core.Category
import app.orb.tracker.core.OrbState

/** Colour tokens from the Orb design system (tokens.json), dark first. */
class Theme private constructor(dark: Boolean) {
    private fun c(darkHex: String, lightHex: String, dark: Boolean) = Color.parseColor(if (dark) darkHex else lightHex)

    val ground = c("#0e1013", "#eef0f3", dark)
    val surface = c("#16191e", "#f7f8fa", dark)
    val surfaceRaised = c("#1e2228", "#ffffff", dark)
    val line = c("#2b3038", "#d9dde3", dark)
    val ink = c("#eef1f5", "#14171c", dark)
    val inkMuted = c("#9aa3af", "#5a6370", dark)
    val good = c("#4ade9a", "#0f7a4d", dark)
    val goodSoft = c("#10301f", "#dcf5e9", dark)
    val bad = c("#ff7a75", "#b42520", dark)
    val badSoft = c("#3a1517", "#fde4e2", dark)
    val neutralSoft = c("#232830", "#e5e8ec", dark)
    val orbGood = c("#3ddc97", "#1fbf7a", dark)
    val orbBad = c("#e5484d", "#c92a2a", dark)
    val orbNeutral = c("#5d6878", "#7a8494", dark)
    val onOrbGood = Color.parseColor("#06281a")
    val onOrbBad = Color.WHITE
    val onOrbNeutral = Color.WHITE
    val focus = c("#7cc4ff", "#0b62c4", dark)

    fun orbFill(s: OrbState) = when (s) {
        OrbState.GOOD -> orbGood
        OrbState.BAD -> orbBad
        OrbState.NEUTRAL, OrbState.IDLE -> orbNeutral
    }

    fun onOrb(s: OrbState) = when (s) {
        OrbState.GOOD -> onOrbGood
        OrbState.BAD -> onOrbBad
        OrbState.NEUTRAL, OrbState.IDLE -> onOrbNeutral
    }

    fun text(c: Category) = when (c) {
        Category.GOOD -> good
        Category.BAD -> bad
        Category.NEUTRAL -> inkMuted
    }

    fun tint(c: Category) = when (c) {
        Category.GOOD -> goodSoft
        Category.BAD -> badSoft
        Category.NEUTRAL -> neutralSoft
    }

    companion object {
        fun of(context: Context): Theme {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return Theme(night != Configuration.UI_MODE_NIGHT_NO)
        }
    }
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun formatMinutes(m: Double): String {
    if (m <= 0) return "0m"
    if (m < 1) return "<1m"
    val h = (m / 60).toInt()
    val mn = Math.round(m % 60).toInt()
    return if (h > 0) "${h}h" + (if (mn > 0) " ${mn}m" else "") else "${mn}m"
}
