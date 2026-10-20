// The UI's only link to the native side. Inside Tauri it calls the Rust commands in
// desktop/src-tauri/src/commands.rs; in a plain browser (`npm run dev`) it serves
// example data so the windows can be designed without the app.
import type { OrbState } from './orb/WallOrb';

export type Category = 'good' | 'bad' | 'neutral';
export type Platform = 'mac' | 'android';
export type DeviceFilter = 'all' | Platform;

export interface Usage {
  key: string;
  name: string;
  category: Category;
  minutes: number;
  goalMin: number | null;
  overLimit: boolean;
}

export interface Current {
  appId: string;
  appName: string;
  ruleId: string | null;
  ruleName: string | null;
  category: Category;
  since: number;
}

export interface OrbUpdate {
  state: OrbState;
  current: Current | null;
  pausedUntil: number | null;
}

export interface TimelineEntry {
  platform: Platform;
  appId: string;
  appName: string;
  ruleId: string | null;
  ruleName: string | null;
  category: Category;
  start: number;
  end: number;
}

export interface DayTotals { label: string; today: boolean; good: number; bad: number; other: number }

export interface DeviceInfo {
  device: string;
  platform: Platform;
  thisDevice: boolean;
  lastSeen: number;
  good: number;
  bad: number;
  other: number;
}

export interface Rule {
  id: string;
  name: string;
  category: Category;
  dailyLimitMin: number | null;
  mac: string[];
  android: string[];
  updatedAt: number;
  deleted: boolean;
}

export interface SyncStatus {
  url: string;
  anonKey: string;
  email: string;
  signedIn: boolean;
  lastSyncAt: number | null;
  error: string | null;
}

export interface Rect { x: number; y: number; w: number; h: number }

export const inTauri = '__TAURI_INTERNALS__' in window;

async function call<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  const { invoke } = await import('@tauri-apps/api/core');
  return invoke<T>(cmd, args);
}

async function listen<T>(event: string, cb: (payload: T) => void): Promise<() => void> {
  const { listen } = await import('@tauri-apps/api/event');
  return listen<T>(event, e => cb(e.payload));
}

// ---- browser preview data (example, not anyone's real day) -------------------------
const at = (h: number, m: number) => { const d = new Date(); d.setHours(h, m, 0, 0); return d.getTime(); };
const nowMs = () => Date.now();
const sample = {
  rules: [] as Rule[],
  timeline: [
    ['android', 'com.instagram.android', 'Instagram', 'social', 7, 12, 7, 31],
    ['android', 'com.amazon.kindle', 'Kindle', 'reading', 7, 31, 7, 46],
    ['mac', 'com.apple.MobileSMS', 'Messages', 'messaging', 8, 40, 8, 52],
    ['mac', 'com.apple.dt.Xcode', 'Xcode', 'code', 8, 52, 10, 5],
    ['mac', 'com.apple.Safari', 'Safari', null, 10, 5, 10, 23],
    ['mac', 'com.apple.dt.Xcode', 'Xcode', 'code', 10, 23, 11, 31],
    ['mac', 'md.obsidian', 'Obsidian', 'notes', 11, 31, 11, 53],
    ['android', 'com.whatsapp', 'WhatsApp', 'messaging', 12, 2, 12, 14],
    ['android', 'com.google.android.youtube', 'YouTube', 'youtube', 12, 14, 12, 52],
    ['mac', 'com.apple.Terminal', 'Terminal', 'terminal', 13, 2, 13, 33],
    ['mac', 'com.valvesoftware.steam', 'Steam', 'games', 13, 33, 13, 51],
  ] as [Platform, string, string, string | null, number, number, number, number][],
  week: [
    { label: 'Tue', good: 312, bad: 96, other: 40 }, { label: 'Wed', good: 268, bad: 121, other: 52 },
    { label: 'Thu', good: 355, bad: 64, other: 31 }, { label: 'Fri', good: 241, bad: 138, other: 47 },
    { label: 'Sat', good: 96, bad: 182, other: 70 }, { label: 'Sun', good: 132, bad: 150, other: 58 },
  ],
  since: at(13, 51),
  pausedUntil: null as number | null,
};
const sampleRule = (id: string) => sample.rules.find(r => r.id === id);
async function sampleRules(): Promise<Rule[]> {
  if (!sample.rules.length) {
    const { rules } = (await import('../../shared/rules.default.json')).default as unknown as { rules: Omit<Rule, 'updatedAt' | 'deleted'>[] };
    sample.rules = rules.map(r => ({ ...r, updatedAt: 0, deleted: false }));
  }
  return sample.rules;
}
async function sampleTimeline(): Promise<TimelineEntry[]> {
  await sampleRules();
  const rows = sample.timeline.map(([platform, appId, appName, ruleId, h1, m1, h2, m2]) => ({ platform, appId, appName, ruleId, start: at(h1, m1), end: at(h2, m2) }));
  if (!sample.pausedUntil) rows.push({ platform: 'mac', appId: 'com.apple.dt.Xcode', appName: 'Xcode', ruleId: 'code', start: sample.since, end: nowMs() });
  return rows.filter(r => r.start < nowMs()).map(r => {
    const rule = r.ruleId ? sampleRule(r.ruleId) : sample.rules.find(x => x[r.platform].includes(r.appId));
    return { ...r, end: Math.min(r.end, nowMs()), ruleId: rule?.id ?? null, ruleName: rule?.name ?? null, category: rule?.category ?? 'neutral' };
  });
}
function sum(rows: TimelineEntry[]) {
  const t = { good: 0, bad: 0, other: 0 };
  for (const r of rows) t[r.category === 'neutral' ? 'other' : r.category] += (r.end - r.start) / 60000;
  return t;
}
// ---------------------------------------------------------------------------------

export async function getOrbState(): Promise<OrbUpdate> {
  if (inTauri) return call('get_orb_state');
  await sampleRules();
  const code = sampleRule('code')!;
  return sample.pausedUntil
    ? { state: 'idle', current: null, pausedUntil: sample.pausedUntil }
    : { state: code.category, current: { appId: 'com.apple.dt.Xcode', appName: 'Xcode', ruleId: 'code', ruleName: code.name, category: code.category, since: sample.since }, pausedUntil: null };
}

/** Subscribes to orb state changes; returns an unsubscribe function. */
export async function onOrbState(cb: (u: OrbUpdate) => void): Promise<() => void> {
  if (inTauri) return listen('orb-state', cb);
  return () => {};
}

/** Fires when synced data or habits changed, so open views can reload. */
export async function onDataChanged(cb: () => void): Promise<() => void> {
  if (inTauri) return listen('data-changed', cb);
  return () => {};
}

export async function getToday(devices: DeviceFilter): Promise<Usage[]> {
  if (inTauri) return call('get_today', { devices });
  const rows = (await sampleTimeline()).filter(r => devices === 'all' || r.platform === devices);
  const acc = new Map<string, Usage>();
  for (const r of rows) {
    const key = r.ruleId ?? r.appId, rule = r.ruleId ? sampleRule(r.ruleId) : undefined;
    const u = acc.get(key) ?? { key, name: r.ruleName ?? r.appName, category: r.category, minutes: 0, goalMin: rule?.dailyLimitMin ?? null, overLimit: false };
    u.minutes += (r.end - r.start) / 60000;
    acc.set(key, u);
  }
  return [...acc.values()].map(u => ({ ...u, overLimit: u.category === 'bad' && u.goalMin != null && u.minutes > u.goalMin })).sort((a, b) => b.minutes - a.minutes);
}

export async function getTimeline(): Promise<TimelineEntry[]> {
  return inTauri ? call('get_timeline') : sampleTimeline();
}

export async function getWeek(): Promise<DayTotals[]> {
  if (inTauri) return call('get_week');
  return [...sample.week.map(d => ({ ...d, today: false })), { label: 'Today', today: true, ...sum(await sampleTimeline()) }];
}

export async function getDevices(): Promise<DeviceInfo[]> {
  if (inTauri) return call('get_devices');
  const rows = await sampleTimeline();
  const mac = sum(rows.filter(r => r.platform === 'mac')), phone = sum(rows.filter(r => r.platform === 'android'));
  return [
    { device: 'mac-example', platform: 'mac', thisDevice: true, lastSeen: nowMs(), ...mac },
    { device: 'android-example', platform: 'android', thisDevice: false, lastSeen: at(12, 57), ...phone },
  ];
}

export async function openMain(): Promise<void> {
  if (inTauri) await call('open_main');
  else window.open('/', '_blank');
}

/** The usable area of the screen the orb is on, in screen points. */
export async function orbScreen(): Promise<Rect> {
  return inTauri ? call('orb_screen') : { x: 0, y: 0, w: window.innerWidth, h: window.innerHeight };
}

/** Moves and sizes the orb window (screen points). */
export async function orbPlace(r: Rect): Promise<void> {
  if (inTauri) await call('orb_place', { ...r });
}

export async function orbAnchor(): Promise<string | null> {
  return inTauri ? call('orb_anchor') : null;
}

export async function setOrbAnchor(anchor: string): Promise<void> {
  if (inTauri) await call('set_orb_anchor', { anchor });
}

export async function showOrbMenu(): Promise<void> {
  if (inTauri) await call('show_orb_menu');
}

/** Pauses tracking for 30 minutes, or resumes it. */
export async function setPaused(paused: boolean): Promise<void> {
  if (inTauri) return call('set_paused', { paused });
  sample.pausedUntil = paused ? nowMs() + 30 * 60000 : null;
  if (!paused) sample.since = nowMs();
}

export async function getRules(): Promise<Rule[]> {
  return inTauri ? call('get_rules') : sampleRules();
}

export async function saveRule(rule: Rule): Promise<void> {
  if (inTauri) return call('save_rule', { rule });
  const i = sample.rules.findIndex(r => r.id === rule.id);
  if (i >= 0) sample.rules[i] = rule; else sample.rules.push(rule);
}

export async function getSync(): Promise<SyncStatus> {
  if (inTauri) return call('get_sync');
  return { url: 'https://example-project.supabase.co', anonKey: '', email: 'you@example.com', signedIn: true, lastSyncAt: nowMs() - 3 * 60000, error: null };
}

export async function signIn(url: string, anonKey: string, email: string, password: string): Promise<SyncStatus> {
  if (inTauri) return call('sign_in', { url, anonKey, email, password });
  throw new Error('Sync needs the desktop app.');
}

export async function syncNow(): Promise<SyncStatus> {
  if (inTauri) return call('sync_now');
  return { ...(await getSync()), lastSyncAt: nowMs() };
}
