package app.orb.tracker

// Readable names for the apps in shared/rules.default.json; anything else is named from
// the last readable part of its bundle id or package name (com.apple.dt.Xcode → Xcode).
// Mirrors ui/src/appNames.ts.
private val KNOWN = mapOf(
    "com.microsoft.VSCode" to "VS Code",
    "com.todesktop.230313mzl4w4u92" to "Cursor",
    "com.jetbrains.*" to "JetBrains IDEs",
    "com.googlecode.iterm2" to "iTerm2",
    "dev.warp.Warp-Stable" to "Warp",
    "com.mitchellh.ghostty" to "Ghostty",
    "notion.id" to "Notion",
    "com.apple.iBooksX" to "Books",
    "com.google.android.apps.books" to "Play Books",
    "com.google.android.keep" to "Keep",
    "com.zhiliaoapp.musically" to "TikTok",
    "com.twitter.android" to "X",
    "com.facebook.katana" to "Facebook",
    "com.reddit.frontpage" to "Reddit",
    "com.snapchat.android" to "Snapchat",
    "com.instagram.android" to "Instagram",
    "com.google.android.youtube" to "YouTube",
    "com.apple.MobileSMS" to "Messages",
    "com.google.android.apps.messaging" to "Messages",
    "net.whatsapp.WhatsApp" to "WhatsApp",
    "com.whatsapp" to "WhatsApp",
    "ru.keepcoder.Telegram" to "Telegram",
    "org.telegram.messenger" to "Telegram",
    "com.tinyspeck.slackmacgap" to "Slack",
    "com.Slack" to "Slack",
    "com.valvesoftware.steam" to "Steam",
    "com.amazon.Kindle" to "Kindle",
    "com.amazon.kindle" to "Kindle",
)

fun appLabel(id: String): String {
    KNOWN[id]?.let { return it }
    val parts = id.removeSuffix("*").split('.').filter { it.isNotEmpty() }
    // skip machine-made segments such as 230313mzl4w4u92
    val last = parts.lastOrNull { !Regex("\\d{4,}").containsMatchIn(it) && it.length > 2 } ?: parts.lastOrNull() ?: id
    return last.replaceFirstChar { it.uppercase() }
}
