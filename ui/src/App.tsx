import { useEffect, useState } from 'react';
import { LiquidOrb } from './orb/LiquidOrb';
import { ActivityRow, Button, StatusChip, formatMinutes } from './components';
import {
  getOrbState, getRules, getSync, getToday, onOrbState, saveRule, signIn, syncNow,
  type Category, type DeviceFilter, type OrbUpdate, type Rule, type SyncStatus, type Usage,
} from './bridge';

type Tab = 'today' | 'habits' | 'sync';

export function App() {
  const [tab, setTab] = useState<Tab>('today');
  const [orb, setOrb] = useState<OrbUpdate>({ state: 'idle', current: null });

  useEffect(() => {
    let off: (() => void) | undefined;
    getOrbState().then(setOrb);
    onOrbState(setOrb).then((f) => (off = f));
    return () => off?.();
  }, []);

  const now = orb.current;
  return (
    <div className="app">
      <header className="app-head">
        <LiquidOrb state={orb.state} size={36} />
        <div>
          <div className="t-title">{orb.state === 'idle' || !now ? 'Idle' : now.ruleName ?? now.appName}</div>
          <div className="t-caption muted">
            {orb.state === 'good' ? 'Good habit: keep going' : orb.state === 'bad' ? 'Habit to reduce: ease off' : orb.state === 'neutral' ? 'Not categorised yet' : 'Nothing tracked right now'}
          </div>
        </div>
        <nav className="tabs" role="tablist">
          {(['today', 'habits', 'sync'] as Tab[]).map((t) => (
            <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>
              {t === 'today' ? 'Today' : t === 'habits' ? 'Habits' : 'Sync'}
            </button>
          ))}
        </nav>
      </header>
      {tab === 'today' && <Today />}
      {tab === 'habits' && <Habits />}
      {tab === 'sync' && <Sync />}
    </div>
  );
}

function Today() {
  const [devices, setDevices] = useState<DeviceFilter>('all');
  const [usage, setUsage] = useState<Usage[] | null>(null);

  useEffect(() => {
    let alive = true;
    const load = () => getToday(devices).then((u) => alive && setUsage(u));
    load();
    // the window is only open while you look at it, so a minute refresh is cheap
    const t = window.setInterval(load, 60_000);
    return () => { alive = false; window.clearInterval(t); };
  }, [devices]);

  const total = (c: Category) => (usage ?? []).filter((u) => u.category === c).reduce((s, u) => s + u.minutes, 0);
  const over = (usage ?? []).filter((u) => u.overLimit);

  return (
    <main className="stack">
      <div className="seg" role="radiogroup" aria-label="Devices">
        {(['all', 'mac', 'android'] as DeviceFilter[]).map((d) => (
          <button key={d} role="radio" aria-checked={devices === d} className={devices === d ? 'on' : ''} onClick={() => setDevices(d)}>
            {d === 'all' ? 'All devices' : d === 'mac' ? 'Mac' : 'Android'}
          </button>
        ))}
      </div>
      <section className="stats">
        <div className="stat"><div className="t-stat good">{formatMinutes(total('good'))}</div><div className="t-caption muted">good habits</div></div>
        <div className="stat"><div className="t-stat bad">{formatMinutes(total('bad'))}</div><div className="t-caption muted">to reduce</div></div>
        <div className="stat"><div className="t-stat">{formatMinutes(total('neutral'))}</div><div className="t-caption muted">other</div></div>
      </section>
      {over.length > 0 && (
        <p className="t-body bad">
          {over.map((u) => `${u.name} is ${formatMinutes(u.minutes - (u.goalMin ?? 0))} over your ${formatMinutes(u.goalMin ?? 0)} limit`).join('. ')}.
        </p>
      )}
      <section className="list">
        {usage === null ? <p className="t-body muted">Loading…</p>
          : usage.length === 0 ? <p className="t-body muted">Nothing tracked yet today.</p>
          : usage.map((u) => (
            <ActivityRow key={u.key} app={u.name} category={u.category === 'good' ? 'Good habit' : u.category === 'bad' ? 'To reduce' : 'Uncategorised'}
              minutes={u.minutes} goal={u.goalMin} state={u.category} />
          ))}
      </section>
    </main>
  );
}

function Habits() {
  const [rules, setRules] = useState<Rule[]>([]);
  useEffect(() => { getRules().then(setRules); }, []);

  async function update(r: Rule, patch: Partial<Rule>) {
    const next = { ...r, ...patch, updatedAt: Date.now() };
    setRules((rs) => rs.map((x) => (x.id === r.id ? next : x)));
    await saveRule(next);
  }

  return (
    <main className="stack">
      <p className="t-body muted">Choose what counts as good and what you want to reduce. Changes sync to your other devices.</p>
      <section className="list">
        {rules.filter((r) => !r.deleted).map((r) => (
          <div key={r.id} className="habit">
            <div>
              <div className="t-label">{r.name}</div>
              <div className="t-caption muted">{[...r.mac, ...r.android].slice(0, 3).join(', ') || 'No apps yet'}</div>
            </div>
            <select aria-label={`${r.name} category`} value={r.category} onChange={(e) => update(r, { category: e.target.value as Category })}>
              <option value="good">Good</option>
              <option value="bad">Reduce</option>
              <option value="neutral">Neutral</option>
            </select>
            <label className="limit">
              <input type="number" min={0} step={5} aria-label={`${r.name} daily limit in minutes`} value={r.dailyLimitMin ?? ''}
                placeholder="—" onChange={(e) => update(r, { dailyLimitMin: e.target.value === '' ? null : Number(e.target.value) })} />
              <span className="t-caption muted">min/day</span>
            </label>
            <StatusChip state={r.category}>{r.category === 'bad' ? 'Reduce' : r.category === 'good' ? 'Good' : 'Neutral'}</StatusChip>
          </div>
        ))}
      </section>
    </main>
  );
}

function Sync() {
  const [s, setS] = useState<SyncStatus | null>(null);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { getSync().then(setS); }, []);
  if (!s) return null;

  const run = async (f: () => Promise<SyncStatus>) => {
    setBusy(true); setErr(null);
    try { setS(await f()); setPassword(''); } catch (e) { setErr(String(e)); } finally { setBusy(false); }
  };

  return (
    <main className="stack">
      <p className="t-body muted">
        Mac and Android sync through your own free Supabase project. Create one, run <code>sync/schema.sql</code> in its SQL editor,
        add a user under Authentication, then sign in with the same details on both devices.
      </p>
      <form className="form" onSubmit={(e) => { e.preventDefault(); run(() => signIn(s.url, s.anonKey, s.email, password)); }}>
        <label><span className="t-label">Project URL</span><input value={s.url} onChange={(e) => setS({ ...s, url: e.target.value })} placeholder="https://xyz.supabase.co" /></label>
        <label><span className="t-label">Anon key</span><input value={s.anonKey} onChange={(e) => setS({ ...s, anonKey: e.target.value })} /></label>
        <label><span className="t-label">Email</span><input type="email" value={s.email} onChange={(e) => setS({ ...s, email: e.target.value })} /></label>
        <label><span className="t-label">Password</span><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={s.signedIn ? 'Signed in' : ''} /></label>
        <div className="row">
          <Button variant="primary" type="submit" disabled={busy || !password}>{s.signedIn ? 'Sign in again' : 'Sign in'}</Button>
          <Button disabled={busy || !s.signedIn} onClick={() => run(syncNow)}>Sync now</Button>
        </div>
      </form>
      <p className="t-caption muted">
        {s.signedIn ? `Signed in as ${s.email}. ` : 'Not signed in. '}
        {s.lastSyncAt ? `Last synced ${new Date(s.lastSyncAt).toLocaleTimeString()}.` : ''}
      </p>
      {(err || s.error) && <p className="t-body bad">{err ?? s.error}</p>}
    </main>
  );
}
