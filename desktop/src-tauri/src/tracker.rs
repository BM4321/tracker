//! Turns app switches, idle and pause into sessions and the orb's state. Pure logic:
//! the caller persists the sessions it returns and emits the state.

use orb_core::{classify, Category, OrbState, Platform, Rule, Session};
use serde::Serialize;

/// Our own bundle id: switching to Orb's window does not end what you were doing.
pub const OWN_BUNDLE_ID: &str = "app.orb.tracker";

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Current {
    pub app_id: String,
    pub app_name: String,
    pub rule_id: Option<String>,
    pub rule_name: Option<String>,
    pub category: Category,
    /// When this stretch in the app began (Unix ms).
    pub since: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OrbUpdate {
    pub state: OrbState,
    pub current: Option<Current>,
    /// Set while tracking is paused (Unix ms).
    pub paused_until: Option<i64>,
}

pub struct Tracker {
    device: String,
    open: Option<(Session, Option<String>)>,
    /// The last app seen in front, so tracking can resume after idle or pause.
    front: Option<(String, String)>,
    idle: bool,
    paused_until: Option<i64>,
}

impl Tracker {
    pub fn new(device: String) -> Self {
        Self { device, open: None, front: None, idle: false, paused_until: None }
    }

    pub fn update(&self) -> OrbUpdate {
        let paused_until = self.paused_until;
        match &self.open {
            Some((s, rule_name)) => OrbUpdate {
                state: s.category.into(),
                current: Some(Current {
                    app_id: s.app_id.clone(),
                    app_name: s.app_name.clone(),
                    rule_id: s.rule_id.clone(),
                    rule_name: rule_name.clone(),
                    category: s.category,
                    since: s.started_at,
                }),
                paused_until,
            },
            None => OrbUpdate { state: OrbState::Idle, current: None, paused_until },
        }
    }

    pub fn open_session(&self) -> Option<&Session> {
        self.open.as_ref().map(|(s, _)| s)
    }

    pub fn is_paused(&self, now: i64) -> bool {
        self.paused_until.is_some_and(|t| now < t)
    }

    /// Ends the open session at `at`; returns it for saving.
    fn close(&mut self, at: i64) -> Option<Session> {
        self.open.take().map(|(mut s, _)| {
            s.ended_at = at.max(s.started_at);
            s
        })
    }

    fn start(&mut self, rules: &[Rule], now: i64) {
        let Some((app_id, app_name)) = self.front.clone() else { return };
        let rule = classify(rules, Platform::Mac, &app_id);
        let session = Session {
            id: uuid::Uuid::new_v4().to_string(),
            device: self.device.clone(),
            platform: Platform::Mac,
            app_id,
            app_name,
            rule_id: rule.map(|r| r.id.clone()),
            category: rule.map_or(Category::Neutral, |r| r.category),
            started_at: now,
            ended_at: now,
        };
        self.open = Some((session, rule.map(|r| r.name.clone())));
    }

    fn tracking(&self, now: i64) -> bool {
        !self.idle && !self.is_paused(now)
    }

    /// A different app came to the front. Returns sessions to save.
    pub fn on_app(&mut self, rules: &[Rule], app_id: &str, app_name: &str, now: i64) -> Vec<Session> {
        if app_id == OWN_BUNDLE_ID || self.open.as_ref().is_some_and(|(s, _)| s.app_id == app_id) {
            return vec![];
        }
        self.front = Some((app_id.to_string(), app_name.to_string()));
        let mut out: Vec<Session> = self.close(now).into_iter().collect();
        if self.tracking(now) {
            self.start(rules, now);
            out.extend(self.open.as_ref().map(|(s, _)| s.clone()));
        }
        out
    }

    /// No input since `since`: the open session ends there.
    pub fn on_idle(&mut self, since: i64) -> Vec<Session> {
        if self.idle {
            return vec![];
        }
        self.idle = true;
        self.close(since).into_iter().collect()
    }

    /// Input again after idle: resume with whatever is in front.
    pub fn on_active(&mut self, rules: &[Rule], now: i64) -> Vec<Session> {
        if !self.idle {
            return vec![];
        }
        self.idle = false;
        if self.tracking(now) {
            self.start(rules, now);
        }
        self.open.iter().map(|(s, _)| s.clone()).collect()
    }

    pub fn pause(&mut self, until: i64, now: i64) -> Vec<Session> {
        self.paused_until = Some(until);
        self.close(now).into_iter().collect()
    }

    pub fn resume(&mut self, rules: &[Rule], now: i64) -> Vec<Session> {
        self.paused_until = None;
        if self.open.is_none() && !self.idle {
            self.start(rules, now);
        }
        self.open.iter().map(|(s, _)| s.clone()).collect()
    }

    /// Periodic checkpoint: the open session, extended to `now`, so a crash loses at most
    /// one interval. Also ends a pause whose time is up.
    pub fn tick(&mut self, rules: &[Rule], now: i64) -> Vec<Session> {
        if self.paused_until.is_some() && !self.is_paused(now) {
            return self.resume(rules, now);
        }
        match &mut self.open {
            Some((s, _)) => {
                s.ended_at = now;
                vec![s.clone()]
            }
            None => vec![],
        }
    }

    /// Rules changed: the open session splits so earlier time keeps its old category.
    pub fn reclassify(&mut self, rules: &[Rule], now: i64) -> Vec<Session> {
        let Some((s, _)) = &self.open else { return vec![] };
        let rule = classify(rules, Platform::Mac, &s.app_id);
        if rule.map(|r| &r.id) == s.rule_id.as_ref() && rule.map_or(Category::Neutral, |r| r.category) == s.category {
            if let Some((_, name)) = &mut self.open {
                *name = rule.map(|r| r.name.clone());
            }
            return vec![];
        }
        let mut out: Vec<Session> = self.close(now).into_iter().collect();
        self.start(rules, now);
        out.extend(self.open.as_ref().map(|(s, _)| s.clone()));
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use orb_core::parse_rules;

    fn rules() -> Vec<Rule> {
        parse_rules(include_str!("../../../shared/rules.default.json")).unwrap()
    }

    #[test]
    fn switching_apps_closes_and_opens_sessions() {
        let rules = rules();
        let mut t = Tracker::new("mac-1".into());
        assert_eq!(t.update().state, OrbState::Idle);

        let out = t.on_app(&rules, "com.apple.dt.Xcode", "Xcode", 0);
        assert_eq!(out.len(), 1);
        assert_eq!(t.update().state, OrbState::Good);
        assert_eq!(t.update().current.unwrap().rule_name.as_deref(), Some("Coding"));

        let out = t.on_app(&rules, "com.valvesoftware.steam", "Steam", 60_000);
        assert_eq!(out[0].ended_at, 60_000);
        assert_eq!(out[0].category, Category::Good);
        assert_eq!(out[1].category, Category::Bad);
        assert_eq!(t.update().state, OrbState::Bad);

        assert!(t.on_app(&rules, OWN_BUNDLE_ID, "Orb", 70_000).is_empty());
        assert_eq!(t.update().state, OrbState::Bad);

        t.on_app(&rules, "com.example.unknown", "Unknown", 80_000);
        assert_eq!(t.update().state, OrbState::Neutral);
    }

    #[test]
    fn idle_ends_at_last_input_and_resumes() {
        let rules = rules();
        let mut t = Tracker::new("mac-1".into());
        t.on_app(&rules, "com.apple.dt.Xcode", "Xcode", 0);
        let out = t.on_idle(300_000);
        assert_eq!(out[0].ended_at, 300_000);
        assert_eq!(t.update().state, OrbState::Idle);
        // switching apps while idle is remembered but not tracked
        assert_eq!(t.on_app(&rules, "com.valvesoftware.steam", "Steam", 400_000).len(), 0);
        let out = t.on_active(&rules, 500_000);
        assert_eq!(out[0].app_id, "com.valvesoftware.steam");
        assert_eq!(out[0].started_at, 500_000);
    }

    #[test]
    fn pause_expires_on_tick() {
        let rules = rules();
        let mut t = Tracker::new("mac-1".into());
        t.on_app(&rules, "com.apple.dt.Xcode", "Xcode", 0);
        t.pause(1_000, 10);
        assert_eq!(t.update().state, OrbState::Idle);
        assert!(t.tick(&rules, 500).is_empty());
        let out = t.tick(&rules, 1_500);
        assert_eq!(out[0].started_at, 1_500);
        assert_eq!(t.update().state, OrbState::Good);
    }

    #[test]
    fn rule_change_splits_open_session() {
        let mut rules = rules();
        let mut t = Tracker::new("mac-1".into());
        t.on_app(&rules, "com.apple.dt.Xcode", "Xcode", 0);
        rules.iter_mut().find(|r| r.id == "code").unwrap().category = Category::Bad;
        let out = t.reclassify(&rules, 100);
        assert_eq!((out[0].category, out[0].ended_at), (Category::Good, 100));
        assert_eq!(out[1].category, Category::Bad);
        assert_eq!(t.update().state, OrbState::Bad);
    }
}
