// The UI's only link to the native side. Inside Tauri it calls the Rust commands in
// desktop/src-tauri/src/commands.rs; in a plain browser (`npm run dev`) it serves
// sample data so the windows can be designed without the app.
import type { OrbState } from './orb/LiquidOrb';

export type Category = 'good' | 'bad' | 'neutral';
export type DeviceFilter = 'all' | 'mac' | 'android';

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
}

export interface OrbUpdate {
  state: OrbState;
  current: Current | null;
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

const inTauri = '__TAURI_INTERNALS__' in window;

async function call<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  const { invoke } = await import('@tauri-apps/api/core');
  return invoke<T>(cmd, args);
}

// ---- browser preview data ---------------------------------------------------------
const sampleUsage: Usage[] = [
  { key: 'code', name: 'Coding', category: 'good', minutes: 134, goalMin: null, overLimit: false },
  { key: 'youtube', name: 'YouTube', category: 'bad', minutes: 78, goalMin: 45, overLimit: true },
  { key: 'notes', name: 'Notes & study', category: 'good', minutes: 41, goalMin: null, overLimit: false },
  { key: 'social', name: 'Social media', category: 'bad', minutes: 18, goalMin: 30, overLimit: false },
  { key: 'com.apple.finder', name: 'Finder', category: 'neutral', minutes: 6, goalMin: null, overLimit: false },
];
let sampleState: OrbUpdate = {
  state: 'good',
  current: { appId: 'com.microsoft.VSCode', appName: 'Code', ruleId: 'code', ruleName: 'Coding', category: 'good' },
};
// ---------------------------------------------------------------------------------

export async function getOrbState(): Promise<OrbUpdate> {
  return inTauri ? call('get_orb_state') : sampleState;
}

/** Subscribes to orb state changes; returns an unsubscribe function. */
export async function onOrbState(cb: (u: OrbUpdate) => void): Promise<() => void> {
  if (inTauri) {
    const { listen } = await import('@tauri-apps/api/event');
    return listen<OrbUpdate>('orb-state', (e) => cb(e.payload));
  }
  // preview: cycle states so the orb can be watched changing
  const order: OrbState[] = ['good', 'bad', 'neutral', 'idle'];
  const t = window.setInterval(() => {
    sampleState = { ...sampleState, state: order[(order.indexOf(sampleState.state) + 1) % order.length] };
    cb(sampleState);
  }, 4000);
  return () => window.clearInterval(t);
}

export async function getToday(devices: DeviceFilter): Promise<Usage[]> {
  if (inTauri) return call('get_today', { devices });
  return devices === 'android' ? sampleUsage.filter((u) => u.category !== 'good') : sampleUsage;
}

export async function openMain(): Promise<void> {
  if (inTauri) await call('open_main');
  else window.open('/', '_blank');
}

/** Moves the orb window along its screen edge; returns the pixels actually moved. */
export async function moveOrb(dy: number): Promise<number> {
  return inTauri ? call('move_orb', { dy }) : 0;
}

/** Grows the orb window while it is handled (room for the liquid drag), shrinks it at rest. */
export async function setOrbExpanded(expanded: boolean): Promise<void> {
  if (inTauri) await call('set_orb_expanded', { expanded });
}

export async function showOrbMenu(): Promise<void> {
  if (inTauri) await call('show_orb_menu');
}

export async function getRules(): Promise<Rule[]> {
  if (inTauri) return call('get_rules');
  const { rules } = (await import('../../shared/rules.default.json')).default as unknown as { rules: Omit<Rule, 'updatedAt' | 'deleted'>[] };
  return rules.map((r) => ({ ...r, updatedAt: 0, deleted: false }));
}

export async function saveRule(rule: Rule): Promise<void> {
  if (inTauri) await call('save_rule', { rule });
}

export async function getSync(): Promise<SyncStatus> {
  if (inTauri) return call('get_sync');
  return { url: '', anonKey: '', email: '', signedIn: false, lastSyncAt: null, error: null };
}

export async function signIn(url: string, anonKey: string, email: string, password: string): Promise<SyncStatus> {
  if (inTauri) return call('sign_in', { url, anonKey, email, password });
  throw new Error('Sync needs the desktop app.');
}

export async function syncNow(): Promise<SyncStatus> {
  if (inTauri) return call('sync_now');
  throw new Error('Sync needs the desktop app.');
}
