//! Platform-independent logic shared by the Orb desktop app: habit rules, the
//! sessions recorded from app switches, and the daily summary the window shows.
//! The Android app mirrors these rules in Kotlin (`android/.../core/`).

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Category {
    Good,
    Bad,
    Neutral,
}

/// What the orb shows right now.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum OrbState {
    Good,
    Bad,
    Neutral,
    Idle,
}

impl From<Category> for OrbState {
    fn from(c: Category) -> Self {
        match c {
            Category::Good => OrbState::Good,
            Category::Bad => OrbState::Bad,
            Category::Neutral => OrbState::Neutral,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Platform {
    Mac,
    Android,
}

/// A habit: a named group of apps with a category and an optional daily limit
/// (for `bad`) or target (for `good`). Matchers are bundle ids on macOS and
/// package names on Android; a trailing `*` matches a prefix.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Rule {
    pub id: String,
    pub name: String,
    pub category: Category,
    #[serde(default)]
    pub daily_limit_min: Option<u32>,
    #[serde(default)]
    pub mac: Vec<String>,
    #[serde(default)]
    pub android: Vec<String>,
    /// Unix ms of the last edit; rules sync last-writer-wins on this.
    #[serde(default)]
    pub updated_at: i64,
    #[serde(default)]
    pub deleted: bool,
}

impl Rule {
    pub fn matches(&self, platform: Platform, app_id: &str) -> bool {
        let list = match platform {
            Platform::Mac => &self.mac,
            Platform::Android => &self.android,
        };
        list.iter().any(|m| match m.strip_suffix('*') {
            Some(prefix) => app_id.starts_with(prefix),
            None => m == app_id,
        })
    }
}

#[derive(Debug, Deserialize)]
struct RulesFile {
    rules: Vec<Rule>,
}

/// Parses `shared/rules.default.json`.
pub fn parse_rules(json: &str) -> serde_json::Result<Vec<Rule>> {
    Ok(serde_json::from_str::<RulesFile>(json)?.rules)
}

/// The first live rule matching the app, if any.
pub fn classify<'a>(rules: &'a [Rule], platform: Platform, app_id: &str) -> Option<&'a Rule> {
    rules.iter().find(|r| !r.deleted && r.matches(platform, app_id))
}

/// Merges rules from another device: per id, the newer `updated_at` wins.
pub fn merge_rules(local: &[Rule], remote: &[Rule]) -> Vec<Rule> {
    let mut by_id: Vec<Rule> = local.to_vec();
    for r in remote {
        match by_id.iter_mut().find(|l| l.id == r.id) {
            Some(l) if r.updated_at > l.updated_at => *l = r.clone(),
            Some(_) => {}
            None => by_id.push(r.clone()),
        }
    }
    by_id
}

/// One stretch of time in one app on one device. Times are Unix ms.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub id: String,
    pub device: String,
    pub platform: Platform,
    pub app_id: String,
    pub app_name: String,
    pub rule_id: Option<String>,
    pub category: Category,
    pub started_at: i64,
    pub ended_at: i64,
}

/// One row of the daily breakdown.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Usage {
    /// The rule id, or the app id for apps no rule covers.
    pub key: String,
    pub name: String,
    pub category: Category,
    pub minutes: f64,
    pub goal_min: Option<u32>,
    pub over_limit: bool,
}

/// Totals sessions overlapping `[from, to)` per habit, longest first. Sessions
/// are clipped to the window, so one that spans midnight counts on both days.
pub fn summarize(sessions: &[Session], rules: &[Rule], from: i64, to: i64) -> Vec<Usage> {
    let mut acc: HashMap<String, Usage> = HashMap::new();
    for s in sessions {
        let start = s.started_at.max(from);
        let end = s.ended_at.min(to);
        if end <= start {
            continue;
        }
        let minutes = (end - start) as f64 / 60_000.0;
        let rule = s.rule_id.as_ref().and_then(|id| rules.iter().find(|r| &r.id == id && !r.deleted));
        let (key, name, category, goal) = match rule {
            Some(r) => (r.id.clone(), r.name.clone(), r.category, r.daily_limit_min),
            None => (s.app_id.clone(), s.app_name.clone(), s.category, None),
        };
        acc.entry(key.clone())
            .or_insert(Usage { key, name, category, minutes: 0.0, goal_min: goal, over_limit: false })
            .minutes += minutes;
    }
    let mut out: Vec<Usage> = acc
        .into_values()
        .map(|mut u| {
            u.over_limit = u.category == Category::Bad && u.goal_min.is_some_and(|g| u.minutes > g as f64);
            u
        })
        .collect();
    out.sort_by(|a, b| b.minutes.total_cmp(&a.minutes));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const DEFAULTS: &str = include_str!("../../shared/rules.default.json");

    fn session(app: &str, rule: Option<&str>, cat: Category, start_min: i64, end_min: i64) -> Session {
        Session {
            id: format!("{app}-{start_min}"),
            device: "mac-1".into(),
            platform: Platform::Mac,
            app_id: app.into(),
            app_name: app.into(),
            rule_id: rule.map(String::from),
            category: cat,
            started_at: start_min * 60_000,
            ended_at: end_min * 60_000,
        }
    }

    #[test]
    fn default_rules_parse_and_classify() {
        let rules = parse_rules(DEFAULTS).unwrap();
        assert_eq!(classify(&rules, Platform::Mac, "com.apple.dt.Xcode").unwrap().id, "code");
        assert_eq!(classify(&rules, Platform::Mac, "com.jetbrains.intellij").unwrap().id, "code");
        assert_eq!(classify(&rules, Platform::Android, "com.instagram.android").unwrap().category, Category::Bad);
        assert!(classify(&rules, Platform::Mac, "com.instagram.android").is_none());
        assert!(classify(&rules, Platform::Mac, "com.example.unknown").is_none());
    }

    #[test]
    fn deleted_rules_do_not_match() {
        let mut rules = parse_rules(DEFAULTS).unwrap();
        rules.iter_mut().find(|r| r.id == "code").unwrap().deleted = true;
        assert!(classify(&rules, Platform::Mac, "com.apple.dt.Xcode").is_none());
    }

    #[test]
    fn merge_keeps_newest_per_id() {
        let base = parse_rules(DEFAULTS).unwrap();
        let mut remote = base.clone();
        remote[0].name = "Remote".into();
        remote[0].updated_at = 10;
        remote.push(Rule { id: "new".into(), name: "New".into(), category: Category::Good, daily_limit_min: None, mac: vec![], android: vec![], updated_at: 5, deleted: false });
        let mut local = base.clone();
        local[1].name = "Local".into();
        local[1].updated_at = 20;
        remote[1].updated_at = 15;
        let merged = merge_rules(&local, &remote);
        assert_eq!(merged[0].name, "Remote");
        assert_eq!(merged[1].name, "Local");
        assert!(merged.iter().any(|r| r.id == "new"));
        assert_eq!(merged.len(), base.len() + 1);
    }

    #[test]
    fn summarize_groups_clips_and_flags_limits() {
        let rules = parse_rules(DEFAULTS).unwrap();
        let sessions = vec![
            session("com.apple.dt.Xcode", Some("code"), Category::Good, 0, 90),
            session("com.microsoft.VSCode", Some("code"), Category::Good, 100, 130),
            session("com.google.android.youtube", Some("youtube"), Category::Bad, 130, 190),
            // straddles the end of the window: only 10 of its minutes count
            session("com.example.x", None, Category::Neutral, 230, 260),
        ];
        let out = summarize(&sessions, &rules, 0, 240 * 60_000);
        assert_eq!(out[0].key, "code");
        assert_eq!(out[0].minutes, 120.0);
        let yt = out.iter().find(|u| u.key == "youtube").unwrap();
        assert_eq!(yt.minutes, 60.0);
        assert!(yt.over_limit);
        let other = out.iter().find(|u| u.key == "com.example.x").unwrap();
        assert_eq!(other.minutes, 10.0);
        assert!(!other.over_limit);
    }
}
