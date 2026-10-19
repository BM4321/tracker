//! Orb for macOS: a liquid orb on the edge of the screen that turns green for good
//! habits and red for ones to reduce; clicking it opens today's breakdown.
//!
//! CPU budget: app switches and screen sleep arrive as OS notifications (no polling);
//! the only timers are a 30 s idle check, a 1 min checkpoint of the open session and a
//! 10 min sync. The main window is created on demand and destroyed when closed.

mod commands;
mod store;
mod sync;
mod tracker;
mod watcher;

use orb_core::{OrbState, Rule, Session};
use rusqlite::Connection;
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
    orb: Mutex<OrbGeom>,
}

/// Where the orb window is, tracked here rather than read back from the window system
/// (which can report stale values right after a change). The orb sits at the window's
/// right edge and vertical centre, so `right` and `cy` are the orb's anchor.
#[derive(Clone, Copy)]
struct OrbGeom {
    right: f64,
    cy: f64,
    size: (f64, f64),
}

impl OrbGeom {
    fn place(&self, win: &tauri::WebviewWindow) -> tauri::Result<()> {
        let (w, h) = self.size;
        win.set_size(LogicalSize::new(w, h))?;
        win.set_position(LogicalPosition::new(self.right - w, self.cy - h / 2.0))
    }
}

/// The orb window at rest (just the orb, its tether and hover droplets) and while handled.
const ORB_REST: (f64, f64) = (100.0, 80.0);
const ORB_HANDLED: (f64, f64) = (320.0, 320.0);

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
                eprintln!("orb: could not save session: {e}");
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

fn create_orb_window(app: &AppHandle) -> tauri::Result<()> {
    let (w, h) = ORB_REST;
    let (mut x, mut y) = (0.0, 200.0);
    if let Some(m) = app.primary_monitor()? {
        let size = m.size().to_logical::<f64>(m.scale_factor());
        let pos = m.position().to_logical::<f64>(m.scale_factor());
        x = pos.x + size.width - w;
        let saved: Option<f64> = store::get_meta(&app.state::<AppState>().db.lock().unwrap(), "orb_y").ok().flatten().and_then(|v| v.parse().ok());
        y = saved.unwrap_or(pos.y + size.height * 0.4).clamp(pos.y, pos.y + size.height - h);
    }
    *app.state::<AppState>().orb.lock().unwrap() = OrbGeom { right: x + w, cy: y + h / 2.0, size: (w, h) };
    WebviewWindowBuilder::new(app, "orb", WebviewUrl::App("orb.html".into()))
        .title("Orb")
        .inner_size(w, h)
        .position(x, y)
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

/// Grows or shrinks the orb window around the orb, keeping it fixed on screen: anchored
/// to the window's right edge and vertical centre.
pub fn set_orb_expanded(app: &AppHandle, expanded: bool) -> tauri::Result<()> {
    let Some(win) = app.get_webview_window("orb") else { return Ok(()) };
    let size = if expanded { ORB_HANDLED } else { ORB_REST };
    let state = app.state::<AppState>();
    let mut geom = state.orb.lock().unwrap();
    if geom.size == size {
        return Ok(());
    }
    geom.size = size;
    geom.place(&win)
}

/// Slides the orb along the screen edge; returns how far it actually moved.
pub fn move_orb(app: &AppHandle, dy: f64) -> tauri::Result<f64> {
    let Some(win) = app.get_webview_window("orb") else { return Ok(0.0) };
    let (top, bottom) = match win.current_monitor()? {
        Some(m) => {
            let mp = m.position().to_logical::<f64>(m.scale_factor());
            let ms = m.size().to_logical::<f64>(m.scale_factor());
            (mp.y, mp.y + ms.height)
        }
        None => (f64::MIN, f64::MAX),
    };
    let state = app.state::<AppState>();
    let mut geom = state.orb.lock().unwrap();
    // keep the orb itself (not the transparent window) on screen
    let cy = (geom.cy + dy).clamp(top + ORB_REST.1 / 2.0, bottom - ORB_REST.1 / 2.0);
    let moved = cy - geom.cy;
    geom.cy = cy;
    geom.place(&win)?;
    let _ = store::set_meta(&state.db.lock().unwrap(), "orb_y", &(cy - ORB_REST.1 / 2.0).to_string());
    Ok(moved)
}

pub fn open_main(app: &AppHandle) -> tauri::Result<()> {
    if let Some(win) = app.get_webview_window("main") {
        win.show()?;
        win.set_focus()?;
    } else {
        WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
            .title("Orb")
            .inner_size(560.0, 680.0)
            .min_inner_size(380.0, 420.0)
            .build()?
            .set_focus()?;
    }
    // catch up on the other devices while you look
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let _ = sync::sync(&app).await;
    });
    Ok(())
}

pub fn show_orb_menu(app: &AppHandle) -> tauri::Result<()> {
    let Some(win) = app.get_webview_window("orb") else { return Ok(()) };
    let paused = app.state::<AppState>().tracker.lock().unwrap().is_paused(now_ms());
    let menu = Menu::with_items(app, &[
        &MenuItem::with_id(app, "open", "Open Orb", true, None::<&str>)?,
        &if paused {
            MenuItem::with_id(app, "resume", "Resume tracking", true, None::<&str>)?
        } else {
            MenuItem::with_id(app, "pause", "Pause for 30 minutes", true, None::<&str>)?
        },
        &MenuItem::with_id(app, "sync", "Sync now", true, None::<&str>)?,
        &PredefinedMenuItem::separator(app)?,
        &MenuItem::with_id(app, "quit", "Quit Orb", true, None::<&str>)?,
    ])?;
    win.popup_menu(&menu)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .on_menu_event(|app, event| match event.id().as_ref() {
            "open" => { let _ = open_main(app); }
            "pause" => pause(app, 30),
            "resume" => resume(app),
            "sync" => {
                let app = app.clone();
                tauri::async_runtime::spawn(async move { let _ = sync::sync(&app).await; });
            }
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
                last_emitted: Mutex::new(Some(OrbUpdate { state: OrbState::Idle, current: None })),
                orb: Mutex::new(OrbGeom { right: 0.0, cy: 0.0, size: ORB_REST }),
            });

            create_orb_window(app.handle())?;

            let handle = app.handle().clone();
            watcher::start(Arc::new(move |e| on_watch(&handle, e)));

            // checkpoint the open session every minute; sync every ten
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                let mut minute = 0u32;
                loop {
                    tokio::time::sleep(Duration::from_secs(60)).await;
                    let state = handle.state::<AppState>();
                    let rules = state.rules.lock().unwrap().clone();
                    let sessions = state.tracker.lock().unwrap().tick(&rules, now_ms());
                    apply(&handle, sessions);
                    minute += 1;
                    if minute % 10 == 0 {
                        let _ = sync::sync(&handle).await;
                    }
                }
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_orb_state,
            commands::get_today,
            commands::open_main,
            commands::set_orb_expanded,
            commands::move_orb,
            commands::show_orb_menu,
            commands::get_rules,
            commands::save_rule,
            commands::get_sync,
            commands::sign_in,
            commands::sync_now,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Orb");
}
