//! The commands the UI calls (see ui/src/bridge.ts).

use crate::sync::{self, SyncStatus};
use crate::tracker::OrbUpdate;
use crate::{store, AppState};
use chrono::{Days, Local, TimeZone};
use orb_core::{summarize, Platform, Rule, Usage};
use tauri::{AppHandle, State};

type Res<T> = Result<T, String>;

fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

#[tauri::command]
pub fn get_orb_state(state: State<AppState>) -> OrbUpdate {
    state.tracker.lock().unwrap().update()
}

/// Today's breakdown (local midnight to midnight), across devices or for one platform.
#[tauri::command]
pub fn get_today(state: State<AppState>, devices: String) -> Res<Vec<Usage>> {
    let today = Local::now().date_naive();
    let midnight = |d: chrono::NaiveDate| {
        Local.from_local_datetime(&d.and_hms_opt(0, 0, 0).unwrap()).earliest().map(|t| t.timestamp_millis())
    };
    let from = midnight(today).ok_or("no local midnight")?;
    let to = midnight(today + Days::new(1)).ok_or("no local midnight")?;
    let platform = match devices.as_str() {
        "mac" => Some(Platform::Mac),
        "android" => Some(Platform::Android),
        _ => None,
    };
    // the open session is checkpointed every minute; count it up to now
    let mut sessions = store::sessions_between(&state.db.lock().unwrap(), from, to, platform).map_err(err)?;
    if let Some(open) = state.tracker.lock().unwrap().open_session() {
        if let Some(s) = sessions.iter_mut().find(|s| s.id == open.id) {
            s.ended_at = crate::now_ms();
        }
    }
    let rules = state.rules.lock().unwrap();
    Ok(summarize(&sessions, &rules, from, to))
}

#[tauri::command]
pub fn open_main(app: AppHandle) -> Res<()> {
    crate::open_main(&app).map_err(err)
}

#[tauri::command]
pub fn set_orb_expanded(app: AppHandle, expanded: bool) -> Res<()> {
    crate::set_orb_expanded(&app, expanded).map_err(err)
}

#[tauri::command]
pub fn move_orb(app: AppHandle, dy: f64) -> Res<f64> {
    crate::move_orb(&app, dy).map_err(err)
}

#[tauri::command]
pub fn show_orb_menu(app: AppHandle) -> Res<()> {
    crate::show_orb_menu(&app).map_err(err)
}

#[tauri::command]
pub fn get_rules(state: State<AppState>) -> Vec<Rule> {
    state.rules.lock().unwrap().clone()
}

#[tauri::command]
pub fn save_rule(app: AppHandle, state: State<AppState>, rule: Rule) -> Res<()> {
    store::save_rule(&state.db.lock().unwrap(), &rule, true).map_err(err)?;
    let mut rules = state.rules.lock().unwrap().clone();
    match rules.iter_mut().find(|r| r.id == rule.id) {
        Some(r) => *r = rule,
        None => rules.push(rule),
    }
    crate::rules_changed(&app, rules);
    Ok(())
}

#[tauri::command]
pub fn get_sync(state: State<AppState>) -> SyncStatus {
    sync::status(&state)
}

#[tauri::command]
pub async fn sign_in(app: AppHandle, url: String, anon_key: String, email: String, password: String) -> Res<SyncStatus> {
    sync::sign_in(&app, url, anon_key, email, password).await
}

#[tauri::command]
pub async fn sync_now(app: AppHandle) -> Res<SyncStatus> {
    sync::sync(&app).await
}
