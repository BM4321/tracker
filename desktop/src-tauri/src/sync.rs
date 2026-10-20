//! Sync with the user's Supabase project (schema in `sync/schema.sql`). Each device pushes
//! its own sessions and pulls everyone else's; rules merge last-writer-wins. Runs every
//! 30 minutes and when the window opens, never in between.

use crate::{store, AppState};
use orb_core::{merge_rules, Category, Platform, Rule, Session};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::sync::atomic::Ordering;
use tauri::{AppHandle, Manager};

const PAGE: usize = 1000;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncStatus {
    pub url: String,
    pub anon_key: String,
    pub email: String,
    pub signed_in: bool,
    pub last_sync_at: Option<i64>,
    pub error: Option<String>,
}

#[derive(Serialize, Deserialize)]
struct RemoteSession {
    id: String,
    device: String,
    platform: Platform,
    app_id: String,
    app_name: String,
    rule_id: Option<String>,
    category: Category,
    started_at: i64,
    ended_at: i64,
    #[serde(default, skip_serializing)]
    updated_at: Option<String>,
}

#[derive(Serialize, Deserialize)]
struct RemoteRule {
    id: String,
    name: String,
    category: Category,
    daily_limit_min: Option<u32>,
    mac: Vec<String>,
    android: Vec<String>,
    deleted: bool,
    updated_at: i64,
}

impl From<RemoteRule> for Rule {
    fn from(r: RemoteRule) -> Self {
        Rule { id: r.id, name: r.name, category: r.category, daily_limit_min: r.daily_limit_min, mac: r.mac, android: r.android, updated_at: r.updated_at, deleted: r.deleted }
    }
}

impl From<&Rule> for RemoteRule {
    fn from(r: &Rule) -> Self {
        RemoteRule { id: r.id.clone(), name: r.name.clone(), category: r.category, daily_limit_min: r.daily_limit_min, mac: r.mac.clone(), android: r.android.clone(), deleted: r.deleted, updated_at: r.updated_at }
    }
}

fn meta(state: &AppState, key: &str) -> String {
    store::get_meta(&state.db.lock().unwrap(), key).ok().flatten().unwrap_or_default()
}

fn set_meta(state: &AppState, key: &str, value: &str) {
    let _ = store::set_meta(&state.db.lock().unwrap(), key, value);
}

pub fn status(state: &AppState) -> SyncStatus {
    let error = meta(state, "sync_error");
    SyncStatus {
        url: meta(state, "sync_url"),
        anon_key: meta(state, "sync_key"),
        email: meta(state, "sync_email"),
        signed_in: !meta(state, "sync_refresh").is_empty(),
        last_sync_at: meta(state, "sync_last").parse().ok(),
        error: (!error.is_empty()).then_some(error),
    }
}

#[derive(Deserialize)]
struct Tokens {
    access_token: String,
    refresh_token: String,
}

async fn token(state: &AppState, url: &str, key: &str, grant: &str, body: serde_json::Value) -> Result<String, String> {
    let res = state
        .http
        .post(format!("{url}/auth/v1/token?grant_type={grant}"))
        .header("apikey", key)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("Could not reach Supabase: {e}"))?;
    if !res.status().is_success() {
        let msg = res.text().await.unwrap_or_default();
        return Err(format!("Sign-in failed: {msg}"));
    }
    let t: Tokens = res.json().await.map_err(|e| e.to_string())?;
    set_meta(state, "sync_refresh", &t.refresh_token);
    Ok(t.access_token)
}

pub async fn sign_in(app: &AppHandle, url: String, key: String, email: String, password: String) -> Result<SyncStatus, String> {
    let state = app.state::<AppState>();
    let url = url.trim().trim_end_matches('/').to_string();
    set_meta(&state, "sync_url", &url);
    set_meta(&state, "sync_key", key.trim());
    set_meta(&state, "sync_email", email.trim());
    token(&state, &url, key.trim(), "password", json!({ "email": email.trim(), "password": password })).await?;
    sync(app).await
}

/// One full round. Errors are also stored so the window can show them.
pub async fn sync(app: &AppHandle) -> Result<SyncStatus, String> {
    let state = app.state::<AppState>();
    if state.syncing.swap(true, Ordering::SeqCst) {
        return Ok(status(&state));
    }
    let result = run(app, &state).await;
    state.syncing.store(false, Ordering::SeqCst);
    match result {
        Ok(()) => {
            set_meta(&state, "sync_last", &chrono::Utc::now().timestamp_millis().to_string());
            set_meta(&state, "sync_error", "");
            Ok(status(&state))
        }
        Err(e) => {
            set_meta(&state, "sync_error", &e);
            Err(e)
        }
    }
}

async fn run(app: &AppHandle, state: &AppState) -> Result<(), String> {
    let (url, key, refresh) = (meta(state, "sync_url"), meta(state, "sync_key"), meta(state, "sync_refresh"));
    if url.is_empty() || refresh.is_empty() {
        return Ok(()); // not set up: nothing to do
    }
    let access = match token(state, &url, &key, "refresh_token", json!({ "refresh_token": refresh })).await {
        Ok(a) => a,
        Err(e) => {
            set_meta(state, "sync_refresh", "");
            return Err(format!("{e} Sign in again."));
        }
    };
    let rest = |path: &str| format!("{url}/rest/v1/{path}");
    let auth = |rb: reqwest::RequestBuilder| rb.header("apikey", &key).bearer_auth(&access);
    let check = |res: reqwest::Response| async move {
        if res.status().is_success() { Ok(res) } else { Err(format!("Sync failed ({}): {}", res.status(), res.text().await.unwrap_or_default())) }
    };

    // 1. push this device's sessions
    loop {
        let batch = store::dirty_sessions(&state.db.lock().unwrap(), PAGE).map_err(|e| e.to_string())?;
        if batch.is_empty() {
            break;
        }
        let body: Vec<RemoteSession> = batch.iter().map(|s| RemoteSession {
            id: s.id.clone(), device: s.device.clone(), platform: s.platform, app_id: s.app_id.clone(), app_name: s.app_name.clone(),
            rule_id: s.rule_id.clone(), category: s.category, started_at: s.started_at, ended_at: s.ended_at, updated_at: None,
        }).collect();
        check(auth(state.http.post(rest("sessions?on_conflict=id")))
            .header("Prefer", "resolution=merge-duplicates,return=minimal").json(&body).send().await.map_err(|e| e.to_string())?).await?;
        let db = state.db.lock().unwrap();
        for s in &batch {
            store::mark_session_clean(&db, &s.id, s.ended_at).map_err(|e| e.to_string())?;
        }
        if batch.len() < PAGE {
            break;
        }
    }

    // 2. pull everyone else's sessions since the last cursor (server time)
    let mut cursor = meta(state, "sync_cursor");
    if cursor.is_empty() {
        cursor = "1970-01-01T00:00:00Z".into();
    }
    loop {
        let rows: Vec<RemoteSession> = check(auth(state.http.get(rest("sessions"))).query(&[
            ("select", "id,device,platform,app_id,app_name,rule_id,category,started_at,ended_at,updated_at"),
            ("device", &format!("neq.{}", state.device)),
            ("updated_at", &format!("gt.{cursor}")),
            ("order", "updated_at.asc"),
            ("limit", &PAGE.to_string()),
        ]).send().await.map_err(|e| e.to_string())?).await?.json().await.map_err(|e| e.to_string())?;
        let n = rows.len();
        {
            let db = state.db.lock().unwrap();
            for r in rows {
                if let Some(u) = &r.updated_at {
                    cursor = u.clone();
                }
                let s = Session { id: r.id, device: r.device, platform: r.platform, app_id: r.app_id, app_name: r.app_name, rule_id: r.rule_id, category: r.category, started_at: r.started_at, ended_at: r.ended_at };
                store::upsert_session(&db, &s, false).map_err(|e| e.to_string())?;
            }
            store::set_meta(&db, "sync_cursor", &cursor).map_err(|e| e.to_string())?;
        }
        if n < PAGE {
            break;
        }
    }

    // 3. rules: push local edits, then pull and merge
    let dirty = store::dirty_rules(&state.db.lock().unwrap()).map_err(|e| e.to_string())?;
    if !dirty.is_empty() {
        let body: Vec<RemoteRule> = dirty.iter().map(RemoteRule::from).collect();
        check(auth(state.http.post(rest("rules?on_conflict=user_id,id")))
            .header("Prefer", "resolution=merge-duplicates,return=minimal").json(&body).send().await.map_err(|e| e.to_string())?).await?;
        let db = state.db.lock().unwrap();
        for r in &dirty {
            store::mark_rule_clean(&db, &r.id, r.updated_at).map_err(|e| e.to_string())?;
        }
    }
    let remote: Vec<RemoteRule> = check(auth(state.http.get(rest("rules")))
        .query(&[("select", "id,name,category,daily_limit_min,mac,android,deleted,updated_at")])
        .send().await.map_err(|e| e.to_string())?).await?.json().await.map_err(|e| e.to_string())?;
    if !remote.is_empty() {
        let remote: Vec<Rule> = remote.into_iter().map(Rule::from).collect();
        let merged = {
            let db = state.db.lock().unwrap();
            let local = store::load_rules(&db).map_err(|e| e.to_string())?;
            let merged = merge_rules(&local, &remote);
            for r in merged.iter().filter(|m| !local.contains(m)) {
                store::save_rule(&db, r, false).map_err(|e| e.to_string())?;
            }
            merged
        };
        crate::rules_changed(app, merged);
    }
    Ok(())
}
