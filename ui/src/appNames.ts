// Readable names for the apps in shared/rules.default.json; anything else is named from
// the last readable part of its bundle id or package name (com.apple.dt.Xcode → Xcode).
const KNOWN: Record<string, string> = {
  'com.microsoft.VSCode': 'VS Code',
  'com.todesktop.230313mzl4w4u92': 'Cursor',
  'com.jetbrains.*': 'JetBrains IDEs',
  'com.googlecode.iterm2': 'iTerm2',
  'dev.warp.Warp-Stable': 'Warp',
  'com.mitchellh.ghostty': 'Ghostty',
  'notion.id': 'Notion',
  'com.apple.iBooksX': 'Books',
  'com.google.android.apps.books': 'Play Books',
  'com.google.android.keep': 'Keep',
  'com.zhiliaoapp.musically': 'TikTok',
  'com.twitter.android': 'X',
  'com.facebook.katana': 'Facebook',
  'com.reddit.frontpage': 'Reddit',
  'com.snapchat.android': 'Snapchat',
  'com.instagram.android': 'Instagram',
  'com.google.android.youtube': 'YouTube',
  'com.apple.MobileSMS': 'Messages',
  'com.google.android.apps.messaging': 'Messages',
  'net.whatsapp.WhatsApp': 'WhatsApp',
  'com.whatsapp': 'WhatsApp',
  'ru.keepcoder.Telegram': 'Telegram',
  'org.telegram.messenger': 'Telegram',
  'com.tinyspeck.slackmacgap': 'Slack',
  'com.Slack': 'Slack',
  'com.valvesoftware.steam': 'Steam',
  'com.amazon.Kindle': 'Kindle',
  'com.amazon.kindle': 'Kindle',
};

export function appLabel(id: string): string {
  if (KNOWN[id]) return KNOWN[id];
  const parts = id.replace(/\*$/, '').split('.').filter(Boolean);
  // skip machine-made segments such as 230313mzl4w4u92
  const last = [...parts].reverse().find(p => !/\d{4,}/.test(p) && p.length > 2) ?? parts[parts.length - 1] ?? id;
  return last.charAt(0).toUpperCase() + last.slice(1);
}
