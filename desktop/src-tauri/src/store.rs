//! Local SQLite storage: sessions, habit rules and a few settings. Rows changed on this
//! device are marked `dirty` until the sync pushes them.

use orb_core::{parse_rules, Category, Platform, Rule, Session};
use rusqlite::{params, Connection, OptionalExtension};
use std::path::Path;

const DEFAULT_RULES: &str = include_str!("../../../shared/rules.default.json");

pub fn open(path: &Path) -> rusqlite::Result<Connection> {
    let conn = Connection::open(path)?;
    conn.execute_batch(
        "PRAGMA journal_mode = WAL;
         CREATE TABLE IF NOT EXISTS sessions (
           id TEXT PRIMARY KEY, device TEXT NOT NULL, platform TEXT NOT NULL,
           app_id TEXT NOT NULL, app_name TEXT NOT NULL, rule_id TEXT, category TEXT NOT NULL,
           started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 1);
         CREATE INDEX IF NOT EXISTS sessions_started ON sessions (started_at);
         CREATE TABLE IF NOT EXISTS rules (
           id TEXT PRIMARY KEY, json TEXT NOT NULL, updated_at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 0);
         CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);",
    )?;
    let empty: bool = conn.query_row("SELECT COUNT(*) = 0 FROM rules", [], |r| r.get(0))?;
    if empty {
        for rule in parse_rules(DEFAULT_RULES).expect("shared/rules.default.json is valid") {
            save_rule(&conn, &rule, false)?;
        }
    }
    Ok(conn)
}

pub fn get_meta(conn: &Connection, key: &str) -> rusqlite::Result<Option<String>> {
    conn.query_row("SELECT value FROM meta WHERE key = ?1", [key], |r| r.get(0)).optional()
}

pub fn set_meta(conn: &Connection, key: &str, value: &str) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO meta (key, value) VALUES (?1, ?2) ON CONFLICT (key) DO UPDATE SET value = excluded.value",
        params![key, value],
    )?;
    Ok(())
}

/// A stable id for this install, used to tell devices apart when syncing.
pub fn device_id(conn: &Connection) -> rusqlite::Result<String> {
    if let Some(id) = get_meta(conn, "device_id")? {
        return Ok(id);
    }
    let id = format!("mac-{}", uuid::Uuid::new_v4());
    set_meta(conn, "device_id", &id)?;
    Ok(id)
}

pub fn load_rules(conn: &Connection) -> rusqlite::Result<Vec<Rule>> {
    let mut stmt = conn.prepare("SELECT json FROM rules ORDER BY rowid")?;
    let rows = stmt.query_map([], |r| r.get::<_, String>(0))?;
    Ok(rows.filter_map(|j| j.ok().and_then(|j| serde_json::from_str(&j).ok())).collect())
}

pub fn save_rule(conn: &Connection, rule: &Rule, dirty: bool) -> rusqlite::Result<()> {
    let json = serde_json::to_string(rule).expect("rules serialize");
    conn.execute(
        "INSERT INTO rules (id, json, updated_at, dirty) VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT (id) DO UPDATE SET json = excluded.json, updated_at = excluded.updated_at, dirty = excluded.dirty",
        params![rule.id, json, rule.updated_at, dirty],
    )?;
    Ok(())
}

pub fn dirty_rules(conn: &Connection) -> rusqlite::Result<Vec<Rule>> {
    let mut stmt = conn.prepare("SELECT json FROM rules WHERE dirty = 1")?;
    let rows = stmt.query_map([], |r| r.get::<_, String>(0))?;
    Ok(rows.filter_map(|j| j.ok().and_then(|j| serde_json::from_str(&j).ok())).collect())
}

/// Clears `dirty` only if the rule was not edited again while the push was in flight.
pub fn mark_rule_clean(conn: &Connection, id: &str, updated_at: i64) -> rusqlite::Result<()> {
    conn.execute("UPDATE rules SET dirty = 0 WHERE id = ?1 AND updated_at = ?2", params![id, updated_at])?;
    Ok(())
}

fn text<T: serde::Serialize>(v: T) -> String {
    serde_json::to_value(v).ok().and_then(|v| v.as_str().map(String::from)).unwrap_or_default()
}

fn parse<T: serde::de::DeserializeOwned>(s: String) -> rusqlite::Result<T> {
    serde_json::from_value(serde_json::Value::String(s))
        .map_err(|e| rusqlite::Error::FromSqlConversionFailure(0, rusqlite::types::Type::Text, Box::new(e)))
}

pub fn upsert_session(conn: &Connection, s: &Session, dirty: bool) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO sessions (id, device, platform, app_id, app_name, rule_id, category, started_at, ended_at, dirty)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
         ON CONFLICT (id) DO UPDATE SET rule_id = excluded.rule_id, category = excluded.category,
           ended_at = excluded.ended_at, dirty = excluded.dirty",
        params![s.id, s.device, text(s.platform), s.app_id, s.app_name, s.rule_id, text(s.category), s.started_at, s.ended_at, dirty],
    )?;
    Ok(())
}

fn row_to_session(r: &rusqlite::Row) -> rusqlite::Result<Session> {
    Ok(Session {
        id: r.get(0)?,
        device: r.get(1)?,
        platform: parse::<Platform>(r.get(2)?)?,
        app_id: r.get(3)?,
        app_name: r.get(4)?,
        rule_id: r.get(5)?,
        category: parse::<Category>(r.get(6)?)?,
        started_at: r.get(7)?,
        ended_at: r.get(8)?,
    })
}

const SESSION_COLS: &str = "id, device, platform, app_id, app_name, rule_id, category, started_at, ended_at";

/// Sessions overlapping `[from, to)`, optionally from one platform only.
pub fn sessions_between(conn: &Connection, from: i64, to: i64, platform: Option<Platform>) -> rusqlite::Result<Vec<Session>> {
    let sql = format!(
        "SELECT {SESSION_COLS} FROM sessions WHERE started_at < ?1 AND ended_at > ?2 AND (?3 IS NULL OR platform = ?3)"
    );
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map(params![to, from, platform.map(text)], row_to_session)?;
    rows.collect()
}

pub fn dirty_sessions(conn: &Connection, limit: usize) -> rusqlite::Result<Vec<Session>> {
    let sql = format!("SELECT {SESSION_COLS} FROM sessions WHERE dirty = 1 ORDER BY started_at LIMIT ?1");
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map([limit as i64], row_to_session)?;
    rows.collect()
}

/// Clears `dirty` only if the session did not grow while the push was in flight.
pub fn mark_session_clean(conn: &Connection, id: &str, ended_at: i64) -> rusqlite::Result<()> {
    conn.execute("UPDATE sessions SET dirty = 0 WHERE id = ?1 AND ended_at = ?2", params![id, ended_at])?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mem() -> Connection {
        open(Path::new(":memory:")).unwrap()
    }

    #[test]
    fn seeds_rules_and_round_trips_sessions() {
        let conn = mem();
        assert!(load_rules(&conn).unwrap().iter().any(|r| r.id == "code"));
        let s = Session {
            id: "s1".into(), device: "mac-1".into(), platform: Platform::Mac, app_id: "com.apple.dt.Xcode".into(),
            app_name: "Xcode".into(), rule_id: Some("code".into()), category: Category::Good, started_at: 1_000, ended_at: 61_000,
        };
        upsert_session(&conn, &s, true).unwrap();
        assert_eq!(sessions_between(&conn, 0, 100_000, Some(Platform::Mac)).unwrap(), vec![s.clone()]);
        assert!(sessions_between(&conn, 0, 100_000, Some(Platform::Android)).unwrap().is_empty());
        assert!(sessions_between(&conn, 70_000, 100_000, None).unwrap().is_empty());

        // a push that raced with the session growing must leave it dirty
        let pushed = dirty_sessions(&conn, 10).unwrap();
        upsert_session(&conn, &Session { ended_at: 90_000, ..s.clone() }, true).unwrap();
        mark_session_clean(&conn, &pushed[0].id, pushed[0].ended_at).unwrap();
        assert_eq!(dirty_sessions(&conn, 10).unwrap().len(), 1);
        mark_session_clean(&conn, "s1", 90_000).unwrap();
        assert!(dirty_sessions(&conn, 10).unwrap().is_empty());
    }

    #[test]
    fn device_id_is_stable() {
        let conn = mem();
        assert_eq!(device_id(&conn).unwrap(), device_id(&conn).unwrap());
    }
}
