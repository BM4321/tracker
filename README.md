# Sapphier

A lightweight habit tracker that lives on the edge of your screen as a liquid orb.
It watches which app is in front and answers one question at a glance: **is this time well spent?**

- **Green**: a good habit (coding, notes, reading). Keep going.
- **Red**: a habit you want to reduce (YouTube, social media, games). It pulses three times, then holds still.
- **Grey**: not categorised, or idle.

## The orb

The orb is pressed flat against the wall: a **half circle** on any screen edge, a **quarter circle** in a corner.

- **Hover** (Mac): droplets drift around its rim, it breathes, and a bubble shows the habit and today's time on it.
- **Hold**: it swells evenly until the long-press fires. On the Mac that opens a menu (open, pause 30 minutes, sync, quit); on Android it pauses or resumes tracking.
- **Drag**: pull it off the wall and it becomes a ball on a neck of liquid. Let go and it flows to the nearest edge or corner.
- **Click / tap**: opens Sapphier.

At rest the orb is flat shapes and costs nothing. The liquid effect only runs while you touch it.

## The app

Four places, a sidebar on the Mac and a tab bar on Android:

| | |
| --- | --- |
| **Today** | What you are doing now (with a pause button), good vs reduce time, a timeline of your day with one lane per device, your daily limits and where the time went. |
| **Week** | Good and reduce time for each of the last 7 days, as a chart or a table. |
| **Habits** | Sort apps nobody has categorised yet in one tap; set each habit to Good, Reduce or Neutral; adjust limits and goals. |
| **Devices** | Today on each device, and sync. |

Times to reduce are hatched as well as red, so nothing relies on telling red from green.

## Light on the machine

Both apps are event-driven: the system tells them when you switch apps, so nothing polls.

- **Mac:** app switches and screen sleep arrive as `NSWorkspace` notifications. The only timers are a 30 s idle check (one counter read), a 5-minute save of the current session (it is also saved on every switch, sleep and quit) and a 30-minute sync. The orb's window is a small square at rest, so it blocks no clicks; it fills the screen only while you drag. The main window is created when you open it and destroyed when you close it.
- **Android:** an accessibility service is told when the foreground app changes. While the screen is off nothing runs. Background sync runs at most every 30 minutes, only on Wi-Fi and never on a low battery; opening the app syncs straight away.
- **Fast to open:** both apps read your day from a small local database (SQLite, built into macOS and Android), so the window opens instantly; syncing happens in the background.

Measured on the desktop build at rest (orb on screen, window closed, Linux test machine): **0.03% of one CPU core** over 30 seconds, and about **50 MB** of the app's own memory across its processes.

## What's in here

| Path | What |
| --- | --- |
| `core/` | Rust: habit rules, sessions, daily summary. |
| `ui/` | The orb and the Sapphier window (React + Vite), built on the Orb design system. |
| `desktop/` | Tauri 2 Mac app: watches the front app, hosts the orb, stores and syncs. |
| `android/` | Kotlin Android app: the same orb and screens, using only the Android framework. |
| `shared/rules.default.json` | The starting habits, used by both apps. |
| `sync/schema.sql` | The Supabase tables both apps sync through. |

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

Open the `android/` folder in [Android Studio](https://developer.android.com/studio), connect your phone with USB debugging on, and press **Run**. The [GitHub Actions workflow](.github/workflows/build.yml) also builds an APK on every push (the run's **orb-android** artifact).

On first launch, tap **Open accessibility settings** and turn on **Sapphier**. If you installed from an APK file and the switch is greyed out, first go to Settings → Apps → Sapphier → ⋮ → **Allow restricted settings**.

## Set up sync

1. Create a free project at [supabase.com](https://supabase.com).
2. In its **SQL editor**, run [`sync/schema.sql`](sync/schema.sql).
3. Under **Authentication → Users**, add a user with an email and password.
4. In Sapphier's **Devices** tab on each device, enter the project URL, the anon key (Project settings → API), the email and the password.

Each device keeps its own record and uploads only what is new. Habit edits sync too; the newest edit wins.

## Choosing habits

Habits match apps by bundle id on the Mac (`com.apple.dt.Xcode`) and package name on Android (`com.instagram.android`); a trailing `*` matches a prefix (`com.jetbrains.*`). Apps no habit covers show up under **Sort these apps** in the Habits tab. To change the starting set, edit [`shared/rules.default.json`](shared/rules.default.json) before first launch.

## Development

```sh
cd core && cargo test                     # rules, merging, daily summary
cd desktop/src-tauri && cargo test        # tracker and storage
cd ui && npm run dev                      # the windows in a browser, with example data
cd android/typecheck && gradle test       # Android code against android-all, plus its unit tests (no SDK needed)
```

## Known limits

- **Websites on the Mac count as the browser.** YouTube in Safari is "Safari", not YouTube. Reading the active tab's URL is the obvious next step.
- **Only macOS is watched on the desktop.** The app runs on Windows and Linux for development, but the orb stays idle.
- **Not yet tried on real devices.** The Mac app type-checks against macOS and runs on Linux, and the Android code compiles against Android's framework classes with its logic unit-tested, but neither has been run on a Mac or a phone yet.
