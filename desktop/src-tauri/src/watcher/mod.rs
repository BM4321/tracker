//! Watches what is in front without polling: the OS tells us about app switches and
//! screen sleep. The only timer is a 30 s idle check, which reads one counter.

use std::sync::Arc;

#[derive(Debug, Clone)]
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub enum Event {
    /// An app came to the front.
    App { id: String, name: String },
    /// No keyboard or mouse input since this time (Unix ms), or the screen slept.
    Idle { since: i64 },
    /// Input again after idle.
    Active,
}

pub type Callback = Arc<dyn Fn(Event) + Send + Sync>;

/// Time without input before it stops counting.
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
pub const IDLE_AFTER_SECS: f64 = 5.0 * 60.0;

#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "macos")]
pub use macos::start;

/// Only macOS is watched; elsewhere the orb stays idle (the UI still runs, for development).
#[cfg(not(target_os = "macos"))]
pub fn start(_cb: Callback) {
    eprintln!("orb: app watching is only implemented on macOS");
}
