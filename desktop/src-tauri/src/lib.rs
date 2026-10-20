//! Sapphier for macOS: a liquid orb pressed against the edge of the screen that turns green
//! for good habits and red for ones to reduce; clicking it opens the Sapphier window.
//!
//! Resource budget: app switches and screen sleep arrive as OS notifications (no polling);
//! the only timers are a 30 s idle check, a 5 min save of the open session (sessions are
//! also saved on every switch, sleep and quit) and a 30 min sync. The main window is
//! created on demand and destroyed when closed.

mod commands;
mod store;
mod sync;
mod tracker;
mod watcher;

use orb_core::{OrbState, Rule, Session};
use rusqlite::Connection;
use serde::Serialize;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::{AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, WebviewUrl, WebviewWindowBuilder};
use tracker::{OrbUpdate, Tracker};

pub struct AppState {
    pub db: Mutex<Connection>,
    pub tracker: Mutex<Tracker>,
    pub rules: Mutex<Vec<Rule>>,
    pub device: String,
    pub http: reqwest::Client,
    pub syncing: AtomicBool,
    last_emitted: Mutex<Option<OrbUpdate>>,
}

/// How often the open session is saved, and how many saves between syncs.
const CHECKPOINT: Duration = Duration::from_secs(5 * 60);
const SYNC_EVERY_CHECKPOINTS: u32 = 6; // 30 minutes

pub fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

/// Saves sessions the tracker produced and tells the windows if the orb changed.
pub fn apply(app: &AppHandle, sessions: Vec<Session>) {
    let state = app.state::<AppState>();
    if !sessions.is_empty() {
        let db = state.db.lock().unwrap();
        for s in &sessions {
            if let Err(e) = store::upsert_session(&db, s, true) {
                eprintln!("sapphier: could not save session: {e}");
            }
        }
    }
    let update = state.tracker.lock().unwrap().update();
    let mut last = state.last_emitted.lock().unwrap();
    if last.as_ref() != Some(&update) {
        let _ = app.emit("orb-state", &update);
        *last = Some(update);
    }
}

/// New rules from an edit or a sync: store them and re-colour what is in front.
pub fn rules_changed(app: &AppHandle, rules: Vec<Rule>) {
    let state = app.state::<AppState>();
    let sessions = state.tracker.lock().unwrap().reclassify(&rules, now_ms());
    *state.rules.lock().unwrap() = rules;
    apply(app, sessions);
    let _ = app.emit("data-changed", ());
}

fn on_watch(app: &AppHandle, event: watcher::Event) {
    let state = app.state::<AppState>();
    let rules = state.rules.lock().unwrap().clone();
    let now = now_ms();
    let sessions = {
        let mut t = state.tracker.lock().unwrap();
        match event {
            watcher::Event::App { id, name } => t.on_app(&rules, &id, &name, now),
            watcher::Event::Idle { since } => t.on_idle(since),
            watcher::Event::Active => t.on_active(&rules, now),
        }
    };
    apply(app, sessions);
}

pub fn pause(app: &AppHandle, minutes: i64) {
    let state = app.state::<AppState>();
    let now = now_ms();
    let sessions = state.tracker.lock().unwrap().pause(now + minutes * 60_000, now);
    apply(app, sessions);
}

pub fn resume(app: &AppHandle) {
    let state = app.state::<AppState>();
    let rules = state.rules.lock().unwrap().clone();
    let sessions = state.tracker.lock().unwrap().resume(&rules, now_ms());
    apply(app, sessions);
}

/// A rectangle in logical screen points.
#[derive(Debug, Clone, Copy, Serialize)]
pub struct Rect {
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

/// The usable area (without the menu bar and Dock) of the screen the orb is on.
pub fn orb_screen(app: &AppHandle) -> tauri::Result<Rect> {
    let monitor = match app.get_webview_window("orb") {
        Some(w) => w.current_monitor()?.or(app.primary_monitor()?),
        None => app.primary_monitor()?,
    };
    let Some(m) = monitor else { return Ok(Rect { x: 0.0, y: 0.0, w: 1440.0, h: 900.0 }) };
    let s = m.scale_factor();
    let area = m.work_area();
    Ok(Rect {
        x: area.position.x as f64 / s,
        y: area.position.y as f64 / s,
        w: area.size.width as f64 / s,
        h: area.size.height as f64 / s,
    })
}

/// The orb window's frame is decided by the orb page (it knows the orb's shape and
/// whether it is being handled); this only applies it.
pub fn orb_place(app: &AppHandle, r: Rect) -> tauri::Result<()> {
    let Some(win) = app.get_webview_window("orb") else { return Ok(()) };
    win.set_size(LogicalSize::new(r.w.max(1.0), r.h.max(1.0)))?;
    win.set_position(LogicalPosition::new(r.x, r.y))?;
    if !win.is_visible()? {
        win.show()?;
    }
    Ok(())
}

fn create_orb_window(app: &AppHandle) -> tauri::Result<()> {
    // hidden until the orb page has measured the screen and placed itself
    WebviewWindowBuilder::new(app, "orb", WebviewUrl::App("orb.html".into()))
        .title("Sapphier")
        .inner_size(60.0, 80.0)
        .visible(false)
        .resizable(false)
        .decorations(false)
        .transparent(true)
        .shadow(false)
        .always_on_top(true)
        .visible_on_all_workspaces(true)
        .skip_taskbar(true)
        .focused(false)
        .accept_first_mouse(true)
        .build()?;
    Ok(())
}

pub fn open_main(app: &AppHandle) -> tauri::Result<()> {
    if let Some(win) = app.get_webview_window("main") {
        win.show()?;
        win.set_focus()?;
    } else {
        WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
            .title("Sapphier")
            .inner_size(820.0, 640.0)
            .min_inner_size(400.0, 480.0)
            .build()?
            .set_focus()?;
    }
    // the window shows the local copy at once; this catches up on the other devices
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        if sync::sync(&app).await.is_ok() {
            let _ = app.emit("data-changed", ());
        }
    });
    Ok(())
}

pub fn show_orb_menu(app: &AppHandle) -> tauri::Result<()> {
    let Some(win) = app.get_webview_window("orb") else { return Ok(()) };
    let paused = app.state::<AppState>().tracker.lock().unwrap().is_paused(now_ms());
    let menu = Menu::with_items(app, &[
        &MenuItem::with_id(app, "open", "Open Sapphier", true, None::<&str>)?,
        &if paused {
            MenuItem::with_id(app, "resume", "Resume tracking", true, None::<&str>)?
        } else {
            MenuItem::with_id(app, "pause", "Pause for 30 minutes", true, None::<&str>)?
        },
        &MenuItem::with_id(app, "sync", "Sync now", true, None::<&str>)?,
        &PredefinedMenuItem::separator(app)?,
        &MenuItem::with_id(app, "quit", "Quit Sapphier", true, None::<&str>)?,
    ])?;
    win.popup_menu(&menu)
}

fn sync_in_background(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        if sync::sync(&app).await.is_ok() {
            let _ = app.emit("data-changed", ());
        }
    });
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .on_menu_event(|app, event| match event.id().as_ref() {
            "open" => { let _ = open_main(app); }
            "pause" => pause(app, 30),
            "resume" => resume(app),
            "sync" => sync_in_background(app),
            "quit" => {
                // close the open session cleanly before exiting
                let state = app.state::<AppState>();
                let sessions = state.tracker.lock().unwrap().on_idle(now_ms());
                apply(app, sessions);
                app.exit(0);
            }
            _ => {}
        })
        .setup(|app| {
            #[cfg(target_os = "macos")]
            app.set_activation_policy(tauri::ActivationPolicy::Accessory); // no Dock icon

            let dir = app.path().app_data_dir()?;
            std::fs::create_dir_all(&dir)?;
            let db = store::open(&dir.join("orb.sqlite"))?;
            let device = store::device_id(&db)?;
            let rules = store::load_rules(&db)?;
            app.manage(AppState {
                db: Mutex::new(db),
                tracker: Mutex::new(Tracker::new(device.clone())),
                rules: Mutex::new(rules),
                device,
                http: reqwest::Client::builder().timeout(Duration::from_secs(30)).build()?,
                syncing: AtomicBool::new(false),
                last_emitted: Mutex::new(Some(OrbUpdate { state: OrbState::Idle, current: None, paused_until: None })),
            });

            create_orb_window(app.handle())?;

            let handle = app.handle().clone();
            watcher::start(Arc::new(move |e| on_watch(&handle, e)));

            // save the open session every 5 minutes; sync every 30
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                let mut n = 0u32;
                loop {
                    tokio::time::sleep(CHECKPOINT).await;
                    let state = handle.state::<AppState>();
                    let rules = state.rules.lock().unwrap().clone();
                    let sessions = state.tracker.lock().unwrap().tick(&rules, now_ms());
                    apply(&handle, sessions);
                    n += 1;
                    if n % SYNC_EVERY_CHECKPOINTS == 0 && sync::sync(&handle).await.is_ok() {
                        let _ = handle.emit("data-changed", ());
                    }
                }
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_orb_state,
            commands::get_today,
            commands::get_timeline,
            commands::get_week,
            commands::get_devices,
            commands::open_main,
            commands::orb_screen,
            commands::orb_place,
            commands::orb_anchor,
            commands::set_orb_anchor,
            commands::show_orb_menu,
            commands::set_paused,
            commands::get_rules,
            commands::save_rule,
            commands::get_sync,
            commands::sign_in,
            commands::sync_now,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Sapphier");
}
