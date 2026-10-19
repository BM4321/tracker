# Orb

A lightweight habit tracker that lives on the edge of your screen as a single liquid orb.
It watches which app is in front and answers one question at a glance: **is this time well spent?**

- **Green**: a good habit (coding, notes, reading). Keep going.
- **Red**: a habit you want to reduce (YouTube, social media, games). It pulses three times, then holds still.
- **Grey**: not categorised, or idle.

Click the orb to open today's breakdown. Drag it up or down to move it along the edge; pull it sideways and it stretches like liquid and springs back.
Right-click it on the Mac, or long-press it on Android, to pause tracking for 30 minutes.
Your Mac and Android phone sync through your own free Supabase project.

The look comes from the **Orb design system** (colours, type, the liquid orb component).

## What's in here

| Path | What |
| --- | --- |
| `core/` | Rust: habit rules, sessions, daily summary. Shared by the Mac app. |
| `ui/` | React + Vite: the orb window and the main window (Today, Habits, Sync). |
| `desktop/` | Tauri 2 Mac app: watches the front app, hosts the orb, stores and syncs. |
| `android/` | Kotlin Android app: the same orb and tracking, no extra libraries. |
| `shared/rules.default.json` | The starting habits, used by both apps. |
| `sync/schema.sql` | The Supabase tables both apps sync through. |

## Light on the machine

Both apps are event-driven: the system tells them when you switch apps, so nothing polls.

- **Mac:** app switches and screen sleep arrive as `NSWorkspace` notifications. The only timers are a 30 s idle check (one counter read), a 1-minute save of the current session and a 10-minute sync. The orb is one small transparent window; the main window is created when you open it and destroyed when you close it. Release builds are size-optimised (`opt-level = "s"`, LTO).
- **Android:** an accessibility service is told when the foreground window changes. While the screen is off nothing runs. Sync is a 15-minute `JobScheduler` job that only runs with a network.
- **The orb:** at rest it is flat shapes. The liquid effect (a blur + threshold "goo" filter and a spring) only runs while you touch it, and switches off about 0.4 s after it settles.

## Run it on your Mac

You need [Node 20+](https://nodejs.org) and [Rust](https://rustup.rs).

```sh
npm install --prefix ui
cd desktop
npm install
npm run dev      # development: the orb appears on the right edge of your screen
npm run build    # a .app and .dmg in desktop/src-tauri/target/release/bundle/
```

The app has no Dock icon, just the orb. Builds aren't signed, so the first time you open
the built app, right-click it in Finder and choose **Open**.

## Run it on Android

Open `android/` in Android Studio and press Run, or run `./gradlew :app:assembleDebug` with the Android SDK installed.
The [GitHub Actions workflow](.github/workflows/build.yml) also builds an APK on every push (see the run's **orb-android** artifact).

On first launch, tap **Open accessibility settings** and turn on **Orb**. That service is how Orb
sees which app is in front and draws the orb over other apps. It only reads app names, never what is on screen.

## Set up sync

1. Create a free project at [supabase.com](https://supabase.com).
2. In its **SQL editor**, run [`sync/schema.sql`](sync/schema.sql).
3. Under **Authentication → Users**, add a user with an email and password.
4. In Orb's **Sync** tab on each device, enter the project URL, the anon key (Project settings → API), the email and the password.

Each device pushes its own sessions and pulls the others' every 10 minutes (Mac) or 15 minutes (Android), and whenever you open the window. Habit edits sync too; the newest edit wins.

## Choosing habits

The **Habits** tab sets each habit to Good, Reduce or Neutral, with an optional daily limit.
Habits match apps by bundle id on the Mac (`com.apple.dt.Xcode`) and package name on Android (`com.instagram.android`); a trailing `*` matches a prefix (`com.jetbrains.*`).
To add apps or habits, edit [`shared/rules.default.json`](shared/rules.default.json) before first launch. After that, rules live in each device's database and sync.

## Development

```sh
cd core && cargo test                     # rules, merging, daily summary
cd desktop/src-tauri && cargo test        # tracker and storage
cd ui && npm run dev                      # the windows in a browser, with sample data
cd android/typecheck && gradle test       # Android code against android-all, plus its unit tests (no SDK needed)
```

## Known limits

- **Websites on the Mac count as the browser.** YouTube in Safari is "Safari", not YouTube. Reading the active tab's URL is the obvious next step.
- **Only macOS is watched on the desktop.** The app runs on Windows and Linux for development, but the orb stays idle.
- **Not yet tried on real devices.** The Mac app type-checks against macOS and runs on Linux, and the Android code compiles against Android's framework classes with its logic unit-tested, but neither has been run on a Mac or a phone yet.
