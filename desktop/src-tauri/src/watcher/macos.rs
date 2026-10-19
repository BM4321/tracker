use super::{Callback, Event, IDLE_AFTER_SECS};
use block2::RcBlock;
use objc2_app_kit::{
    NSWorkspace, NSWorkspaceDidActivateApplicationNotification, NSWorkspaceScreensDidSleepNotification,
    NSWorkspaceWillSleepNotification,
};
use objc2_foundation::{NSNotification, NSNotificationName, NSOperationQueue};
use std::ptr::NonNull;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

#[link(name = "CoreGraphics", kind = "framework")]
extern "C" {
    // CGEventSourceSecondsSinceLastEventType(kCGEventSourceStateCombinedSessionState, kCGAnyInputEventType)
    fn CGEventSourceSecondsSinceLastEventType(state: i32, event_type: u32) -> f64;
}

/// Set when time stops counting (idle or screen asleep); cleared on the next input.
static IDLE: AtomicBool = AtomicBool::new(false);

fn went_idle(since: i64) -> Option<Event> {
    (!IDLE.swap(true, Ordering::SeqCst)).then_some(Event::Idle { since })
}

fn seconds_since_input() -> f64 {
    unsafe { CGEventSourceSecondsSinceLastEventType(0, u32::MAX) }
}

fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

fn frontmost() -> Option<Event> {
    let app = NSWorkspace::sharedWorkspace().frontmostApplication()?;
    let id = app.bundleIdentifier()?.to_string();
    let name = app.localizedName().map(|n| n.to_string()).unwrap_or_else(|| id.clone());
    Some(Event::App { id, name })
}

fn observe(name: &NSNotificationName, cb: Callback, make: fn() -> Option<Event>) {
    let block = RcBlock::new(move |_n: NonNull<NSNotification>| {
        if let Some(e) = make() {
            cb(e);
        }
    });
    let center = NSWorkspace::sharedWorkspace().notificationCenter();
    let token = unsafe {
        center.addObserverForName_object_queue_usingBlock(Some(name), None, Some(&NSOperationQueue::mainQueue()), &block)
    };
    // observers live for the whole run of the app
    std::mem::forget(token);
}

/// Must be called on the main thread (Tauri's setup hook is).
pub fn start(cb: Callback) {
    unsafe {
        observe(NSWorkspaceDidActivateApplicationNotification, cb.clone(), frontmost);
        observe(NSWorkspaceScreensDidSleepNotification, cb.clone(), || went_idle(now_ms()));
        observe(NSWorkspaceWillSleepNotification, cb.clone(), || went_idle(now_ms()));
    }
    if let Some(e) = frontmost() {
        cb(e);
    }

    std::thread::Builder::new()
        .name("orb-idle".into())
        .spawn(move || {
            loop {
                std::thread::sleep(Duration::from_secs(30));
                let secs = seconds_since_input();
                if secs >= IDLE_AFTER_SECS {
                    if let Some(e) = went_idle(now_ms() - (secs * 1000.0) as i64) {
                        cb(e);
                    }
                } else if secs < 30.0 && IDLE.swap(false, Ordering::SeqCst) {
                    // back at the keyboard (after idle or a wake from sleep)
                    cb(Event::Active);
                }
            }
        })
        .expect("spawn idle thread");
}
