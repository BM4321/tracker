//! The commands the UI calls (see ui/src/bridge.ts).

use crate::sync::{self, SyncStatus};
use crate::tracker::OrbUpdate;
use crate::{store, AppState, Rect};
use chrono::{Datelike, Days, Local, NaiveDate, TimeZone};
use orb_core::{summarize, Category, Platform, Rule, Session, Usage};
use serde::Serialize;
use tauri::{AppHandle, State};

type Res<T> = Result<T, String>;

fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}

fn midnight(d: NaiveDate) -> Res<i64> {
    Local
        .from_local_datetime(&d.and_hms_opt(0, 0, 0).unwrap())
        .earliest()
        .map(|t| t.timestamp_millis())
        .ok_or_else(|| "no local midnight".to_string())
}

/// Local midnight to midnight for the day `days_ago` days back.
fn day_bounds(days_ago: u64) -> Res<(i64, i64, NaiveDate)> {
    let day = Local::now().date_naive() - Days::new(days_ago);
    Ok((midnight(day)?, midnight(day + Days::new(1))?, day))
}

/// Sessions overlapping a window, with the open one counted up to now (it is only
/// saved every few minutes).
fn sessions(state: &AppState, from: i64, to: i64, platform: Option<Platform>) -> Res<Vec<Session>> {
    let mut out = store::sessions_between(&state.db.lock().unwrap(), from, to, platform).map_err(err)?;
    if let Some(open) = state.tracker.lock().unwrap().open_session() {
        let now = crate::now_ms();
        match out.iter_mut().find(|s| s.id == open.id) {
            Some(s) => s.ended_at = now,
            None if platform.is_none_or(|p| p == open.platform) && open.started_at < to => {
                out.push(Session { ended_at: now, ..open.clone() })
            }
            None => {}
        }
    }
    Ok(out)
}

#[tauri::command]
pub fn get_orb_state(state: State<AppState>) -> OrbUpdate {
    state.tracker.lock().unwrap().update()
}

/// Today's breakdown, across devices or for one platform.
#[tauri::command]
pub fn get_today(state: State<AppState>, devices: String) -> Res<Vec<Usage>> {
    let (from, to, _) = day_bounds(0)?;
    let platform = match devices.as_str() {
        "mac" => Some(Platform::Mac),
        "android" => Some(Platform::Android),
        _ => None,
    };
    let list = sessions(&state, from, to, platform)?;
    let rules = state.rules.lock().unwrap();
    Ok(summarize(&list, &rules, from, to))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TimelineEntry {
    platform: Platform,
    app_id: String,
    app_name: String,
    rule_id: Option<String>,
    rule_name: Option<String>,
    category: Category,
    start: i64,
    end: i64,
}

/// Today's sessions on every device, for the day timeline and for finding apps that
/// no habit covers yet.
#[tauri::command]
pub fn get_timeline(state: State<AppState>) -> Res<Vec<TimelineEntry>> {
    let (from, to, _) = day_bounds(0)?;
    let mut list = sessions(&state, from, to, None)?;
    list.sort_by_key(|s| s.started_at);
    let rules = state.rules.lock().unwrap();
    Ok(list
        .into_iter()
        .map(|s| {
            let rule = s.rule_id.as_ref().and_then(|id| rules.iter().find(|r| &r.id == id && !r.deleted));
            TimelineEntry {
                platform: s.platform,
                app_id: s.app_id,
                app_name: s.app_name,
                rule_name: rule.map(|r| r.name.clone()),
                // the habit's current category, so recategorising recolours the day
                category: rule.map_or(s.category, |r| r.category),
                rule_id: rule.map(|r| r.id.clone()),
                start: s.started_at.max(from),
                end: s.ended_at.min(to),
            }
        })
        .collect())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DayTotals {
    label: String,
    today: bool,
    good: f64,
    bad: f64,
    other: f64,
}

fn totals(usage: &[Usage]) -> (f64, f64, f64) {
    usage.iter().fold((0.0, 0.0, 0.0), |(g, b, o), u| match u.category {
        Category::Good => (g + u.minutes, b, o),
        Category::Bad => (g, b + u.minutes, o),
        Category::Neutral => (g, b, o + u.minutes),
    })
}

/// Good, reduce and other minutes for each of the last 7 days, oldest first.
#[tauri::command]
pub fn get_week(state: State<AppState>) -> Res<Vec<DayTotals>> {
    let rules = state.rules.lock().unwrap().clone();
    (0..7u64)
        .rev()
        .map(|ago| {
            let (from, to, day) = day_bounds(ago)?;
            let (good, bad, other) = totals(&summarize(&sessions(&state, from, to, None)?, &rules, from, to));
            let label = if ago == 0 { "Today".to_string() } else { day.weekday().to_string() };
            Ok(DayTotals { label, today: ago == 0, good, bad, other })
        })
        .collect()
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceInfo {
    device: String,
    platform: Platform,
    this_device: bool,
    last_seen: i64,
    good: f64,
    bad: f64,
    other: f64,
}

/// Every device seen in the last week, with today's totals for each.
#[tauri::command]
pub fn get_devices(state: State<AppState>) -> Res<Vec<DeviceInfo>> {
    let (week_from, _, _) = day_bounds(6)?;
    let (from, to, _) = day_bounds(0)?;
    let week = sessions(&state, week_from, to, None)?;
    let today = sessions(&state, from, to, None)?;
    let rules = state.rules.lock().unwrap();
    let mut out: Vec<DeviceInfo> = Vec::new();
    for s in &week {
        match out.iter_mut().find(|d| d.device == s.device) {
            Some(d) => d.last_seen = d.last_seen.max(s.ended_at),
            None => out.push(DeviceInfo { device: s.device.clone(), platform: s.platform, this_device: s.device == state.device, last_seen: s.ended_at, good: 0.0, bad: 0.0, other: 0.0 }),
        }
    }
    if !out.iter().any(|d| d.this_device) {
        out.push(DeviceInfo { device: state.device.clone(), platform: Platform::Mac, this_device: true, last_seen: crate::now_ms(), good: 0.0, bad: 0.0, other: 0.0 });
    }
    for d in &mut out {
        let mine: Vec<Session> = today.iter().filter(|s| s.device == d.device).cloned().collect();
        (d.good, d.bad, d.other) = totals(&summarize(&mine, &rules, from, to));
    }
    out.sort_by_key(|d| (!d.this_device, -d.last_seen));
    Ok(out)
}

#[tauri::command]
pub fn open_main(app: AppHandle) -> Res<()> {
    crate::open_main(&app).map_err(err)
}

#[tauri::command]
pub fn orb_screen(app: AppHandle) -> Res<Rect> {
    crate::orb_screen(&app).map_err(err)
}

#[tauri::command]
pub fn orb_place(app: AppHandle, x: f64, y: f64, w: f64, h: f64) -> Res<()> {
    crate::orb_place(&app, Rect { x, y, w, h }).map_err(err)
}

/// Where the orb was left: JSON written by the orb page, or nothing on first run.
#[tauri::command]
pub fn orb_anchor(state: State<AppState>) -> Option<String> {
    store::get_meta(&state.db.lock().unwrap(), "orb_anchor").ok().flatten()
}

#[tauri::command]
pub fn set_orb_anchor(state: State<AppState>, anchor: String) -> Res<()> {
    store::set_meta(&state.db.lock().unwrap(), "orb_anchor", &anchor).map_err(err)
}

#[tauri::command]
pub fn show_orb_menu(app: AppHandle) -> Res<()> {
    crate::show_orb_menu(&app).map_err(err)
}

#[tauri::command]
pub fn set_paused(app: AppHandle, paused: bool) {
    if paused { crate::pause(&app, 30) } else { crate::resume(&app) }
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
