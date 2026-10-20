import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  getDevices, getOrbState, getRules, getSync, getTimeline, getToday, getWeek, onDataChanged, onOrbState,
  saveRule, setPaused, signIn, syncNow,
  type Category, type DayTotals, type DeviceInfo, type OrbUpdate, type Rule, type SyncStatus, type TimelineEntry, type Usage,
} from './bridge';
import { GLYPH } from './orb/WallOrb';
import { clockTime, formatMinutes as fmt } from './format';
import { appLabel } from './appNames';

type Tab = 'today' | 'week' | 'habits' | 'devices';
const TABS: [Tab, string][] = [['today', 'Today'], ['week', 'Week'], ['habits', 'Habits'], ['devices', 'Devices']];
const CAT_WORD: Record<Category, string> = { good: 'Good habit', bad: 'To reduce', neutral: 'Other' };
const FILL: Record<Category, string> = { good: 'f-good', bad: 'f-bad', neutral: 'f-other' };

interface Data {
  orb: OrbUpdate;
  usage: Usage[];
  timeline: TimelineEntry[];
  week: DayTotals[];
  devices: DeviceInfo[];
  rules: Rule[];
  sync: SyncStatus | null;
}

const EMPTY: Data = { orb: { state: 'idle', current: null, pausedUntil: null }, usage: [], timeline: [], week: [], devices: [], rules: [], sync: null };

/** Loads everything the window shows from the local copy (fast), and reloads when the
 *  orb changes, a sync brings news, or once a minute while the window is visible. */
function useData() {
  const [data, setData] = useState<Data>(EMPTY);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    try {
      const [orb, usage, timeline, week, devices, rules, sync] = await Promise.all([
        getOrbState(), getToday('all'), getTimeline(), getWeek(), getDevices(), getRules(), getSync(),
      ]);
      setData({ orb, usage, timeline, week, devices, rules, sync });
      setError(null);
      setLoaded(true);
    } catch (e) {
      setError(String(e));
    }
  }, []);
  useEffect(() => {
    void load();
    const offs: (() => void)[] = [];
    onOrbState(() => void load()).then(f => offs.push(f));
    onDataChanged(() => void load()).then(f => offs.push(f));
    const t = window.setInterval(() => { if (document.visibilityState === 'visible') void load(); }, 60_000);
    return () => { offs.forEach(f => f()); window.clearInterval(t); };
  }, [load]);
  return { data, loaded, error, reload: load };
}

const totals = (u: { category: Category; minutes: number }[]) => ({
  good: u.filter(x => x.category === 'good').reduce((s, x) => s + x.minutes, 0),
  bad: u.filter(x => x.category === 'bad').reduce((s, x) => s + x.minutes, 0),
  other: u.filter(x => x.category === 'neutral').reduce((s, x) => s + x.minutes, 0),
});

// ---- icons -------------------------------------------------------------------------
const Icon = ({ name }: { name: Tab | 'pause' | 'play' | 'sync' | 'alert' | 'check' | 'laptop' | 'phone' }) => {
  const p = { fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
  const body: Record<string, ReactNode> = {
    today: <><circle cx="12" cy="12" r="8.5" {...p} /><path d="M12 7.5V12l3 2" {...p} /></>,
    week: <path d="M5 20V11M10 20V5M15 20v-7M20 20V8" {...p} />,
    habits: <><path d="M4 7h10M18 7h2M4 17h4M12 17h8" {...p} /><circle cx="16" cy="7" r="2" {...p} /><circle cx="10" cy="17" r="2" {...p} /></>,
    devices: <><rect x="3" y="5" width="13" height="10" rx="1.5" {...p} /><path d="M1.5 18h16" {...p} /><rect x="17.5" y="9" width="5" height="10" rx="1.2" {...p} /></>,
    laptop: <><rect x="4" y="5" width="16" height="11" rx="1.5" {...p} /><path d="M2 19h20" {...p} /></>,
    phone: <><rect x="7" y="3" width="10" height="18" rx="2" {...p} /><path d="M11 18h2" {...p} /></>,
    pause: <path d="M9 6v12M15 6v12" {...p} strokeWidth={2.4} />,
    play: <path d="M8 5.5v13l10-6.5z" fill="currentColor" />,
    sync: <><path d="M20 12a8 8 0 0 1-14 5.3M4 12a8 8 0 0 1 14-5.3" {...p} /><path d="M18 3v4h-4M6 21v-4h4" {...p} /></>,
    alert: <path d="M12 6v8M12 18v.01" {...p} strokeWidth={2.6} />,
    check: <path d="M6 12.5l4 4 8-9" {...p} strokeWidth={2.6} />,
  };
  return <svg viewBox="0 0 24 24" aria-hidden="true">{body[name]}</svg>;
};

// ---- the window ----------------------------------------------------------------------
export function App() {
  const [tab, setTab] = useState<Tab>(() => {
    try { return (localStorage.getItem('sapphier.tab') as Tab) || 'today'; } catch { return 'today'; }
  });
  const { data, loaded, error, reload } = useData();
  const choose = (t: Tab) => {
    setTab(t);
    try { localStorage.setItem('sapphier.tab', t); } catch { /* private mode */ }
  };
  const lastSync = data.sync?.lastSyncAt;
  const nav = TABS.map(([k, label]) => (
    <button key={k} className="nav" aria-current={tab === k ? 'page' : undefined} onClick={() => choose(k)}>
      <Icon name={k} /><span>{label}</span>
    </button>
  ));
  return (
    <div className="shell" onPointerOver={showTip} onPointerLeave={hideTip}>
      <nav className="side" aria-label="Sapphier">
        <div className="brand"><i />Sapphier</div>
        {nav}
        <div className="foot">
          {data.sync?.signedIn
            ? <><span><span className="sync-dot" />Synced</span><span className="mono">{lastSync ? clockTime(lastSync) : '—'}</span></>
            : <button className="linkish" onClick={() => choose('devices')}>Set up sync</button>}
        </div>
      </nav>
      <main className="content" id="content">
        {error && !loaded ? <div className="panel"><p className="error">Sapphier couldn't read today's data.</p><p className="note">{error}</p><div><button className="btn sm" onClick={() => void reload()}>Try again</button></div></div>
          : !loaded ? <p className="note">Loading…</p>
          : tab === 'today' ? <Today data={data} reload={reload} />
          : tab === 'week' ? <Week week={data.week} />
          : tab === 'habits' ? <Habits data={data} reload={reload} />
          : <Devices data={data} reload={reload} />}
      </main>
      <div id="tip" className="tip" role="tooltip" hidden />
    </div>
  );
}

// tooltips for chart marks: any element with data-tip
function showTip(e: React.PointerEvent) {
  const el = (e.target as HTMLElement).closest<HTMLElement>('[data-tip]'), tip = document.getElementById('tip')!;
  if (!el) { tip.hidden = true; return; }
  tip.textContent = '';
  const [head, ...rest] = el.dataset.tip!.split('\n');
  const b = document.createElement('b');
  b.textContent = head;
  tip.append(b, ...rest.map(line => { const d = document.createElement('div'); d.textContent = line; return d; }));
  tip.hidden = false;
  const r = el.getBoundingClientRect();
  tip.style.left = Math.min(Math.max(r.left + r.width / 2, tip.offsetWidth / 2 + 8), window.innerWidth - tip.offsetWidth / 2 - 8) + 'px';
  tip.style.top = Math.max(r.top, tip.offsetHeight + 16) + 'px';
}
function hideTip() { const t = document.getElementById('tip'); if (t) t.hidden = true; }

// ---- Today ---------------------------------------------------------------------------
function NowCard({ orb, reload }: { orb: OrbUpdate; reload: () => void }) {
  const [, tick] = useState(0);
  useEffect(() => { const t = window.setInterval(() => tick(n => n + 1), 30_000); return () => window.clearInterval(t); }, []);
  const c = orb.current, s = orb.state;
  const toggle = async () => { await setPaused(!orb.pausedUntil); reload(); };
  return (
    <div className="nowcard">
      <div className={`dot s-${s}`}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d={GLYPH[s]} fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </div>
      <div className="grow">
        <div className="label">{c ? `Now · ${CAT_WORD[c.category]}` : 'Not tracking'}</div>
        <div className="what">{c ? c.ruleName ?? c.appName : orb.pausedUntil ? 'Paused' : 'Idle'}</div>
        <div className="sub">
          {c ? <>{c.ruleName && c.ruleName !== c.appName ? `${c.appName} · ` : ''}<b>{fmt((Date.now() - c.since) / 60000)}</b> this session</>
            : orb.pausedUntil ? <>Paused until <b>{clockTime(orb.pausedUntil)}</b></>
            : 'Time is not counting while you are away'}
        </div>
      </div>
      <button className="btn sm" onClick={toggle} disabled={!c && !orb.pausedUntil}>
        <Icon name={orb.pausedUntil ? 'play' : 'pause'} />{orb.pausedUntil ? 'Resume' : 'Pause 30m'}
      </button>
    </div>
  );
}

function Balance({ t }: { t: { good: number; bad: number; other: number } }) {
  const sum = t.good + t.bad + t.other || 1;
  const segs: [Category, number][] = ([['good', t.good], ['bad', t.bad], ['neutral', t.other]] as [Category, number][]).filter(x => x[1] > 0);
  return (
    <>
      <div className="balance" role="img" aria-label={`Good ${fmt(t.good)}, to reduce ${fmt(t.bad)}, other ${fmt(t.other)}`}>
        {segs.map(([k, m]) => <i key={k} className={FILL[k]} style={{ flex: m }} data-tip={`${CAT_WORD[k]}\n${fmt(m)} · ${Math.round((m / sum) * 100)}%`} />)}
      </div>
      <div className="legend">
        <span><i className="f-good" />Good {fmt(t.good)}</span>
        <span><i className="f-bad" />To reduce {fmt(t.bad)}</span>
        <span><i className="f-other" />Other {fmt(t.other)}</span>
      </div>
    </>
  );
}

function Timeline({ rows }: { rows: TimelineEntry[] }) {
  // from 6am, or from the hour of the first session if you were up earlier, to midnight
  const midnight = new Date(); midnight.setHours(0, 0, 0, 0);
  const first = rows.length ? Math.min(...rows.map(r => r.start)) : Infinity;
  const startHour = Math.max(0, Math.min(6, Math.floor((first - midnight.getTime()) / 3600_000)));
  const from = midnight.getTime() + startHour * 3600_000, to = midnight.getTime() + 24 * 3600_000, span = to - from;
  const ticks = Array.from({ length: Math.floor((24 - startHour) / 3) + 1 }, (_, i) => startHour + i * 3).filter(h => h <= 24);
  const pct = (t: number) => `${((Math.min(Math.max(t, from), to) - from) / span) * 100}%`;
  const lanes: [string, TimelineEntry[]][] = [['Mac', rows.filter(r => r.platform === 'mac')], ['Phone', rows.filter(r => r.platform === 'android')]];
  const now = Date.now();
  return (
    <div className="tl" role="img" aria-label="Today's timeline on each device">
      {lanes.map(([name, list]) => (
        <div className="tl-row" key={name}>
          <div className="lane-name">{name}</div>
          <div className="lane">
            {list.filter(r => r.end > from).map((r, i) => (
              <i key={i} className={`seg ${FILL[r.category]}`}
                style={{ left: pct(r.start), width: `${Math.max(((Math.min(r.end, to) - Math.max(r.start, from)) / span) * 100, 0.35)}%` }}
                data-tip={`${r.appName}${r.ruleName && r.ruleName !== r.appName ? ' · ' + r.ruleName : ''}\n${clockTime(r.start)}–${clockTime(r.end)} · ${fmt((r.end - r.start) / 60000)}`} />
            ))}
            <i className="nowline" style={{ left: pct(now) }} data-tip={`Now\n${clockTime(now)}`} />
          </div>
        </div>
      ))}
      <div className="axis">{ticks.map(h => <span key={h} style={{ left: pct(midnight.getTime() + h * 3600_000) }}>{String(h % 24).padStart(2, '0')}</span>)}</div>
    </div>
  );
}

function Limits({ rules, usage }: { rules: Rule[]; usage: Usage[] }) {
  const limited = rules.filter(r => !r.deleted && r.category === 'bad' && r.dailyLimitMin != null);
  if (!limited.length) return <p className="note">No limits set. Add one in Habits.</p>;
  return (
    <>
      {limited.map(r => {
        const used = usage.find(u => u.key === r.id)?.minutes ?? 0, limit = r.dailyLimitMin!, over = used > limit;
        const scale = Math.max(limit * 1.25, used);
        return (
          <div className="limit" key={r.id}>
            <div className="row-between">
              <span className="name">{r.name}</span>
              {over ? <span className="chip bad"><Icon name="alert" />Over by {fmt(used - limit)}</span>
                : used >= limit * 0.8 ? <span className="chip neutral">{fmt(limit - used)} left</span>
                : <span className="chip good"><Icon name="check" />{fmt(limit - used)} left</span>}
            </div>
            <div className="track">
              <i className="f-bad" style={{ width: `${(used / scale) * 100}%` }} />
              <span className="mark" style={{ left: `${(limit / scale) * 100}%` }} data-tip={`Limit\n${fmt(limit)} a day`} />
            </div>
            <div className="used"><b>{fmt(used)}</b> of {fmt(limit)}</div>
          </div>
        );
      })}
    </>
  );
}

function Breakdown({ usage }: { usage: Usage[] }) {
  if (!usage.length) return <p className="note">Nothing tracked yet today.</p>;
  const max = Math.max(1, ...usage.map(u => u.minutes));
  return (
    <div className="list">
      {usage.map(u => (
        <div className="item" key={u.key}>
          <span className={`ic c-${u.category}`}>{u.name.slice(0, 1).toUpperCase()}</span>
          <div className="nm">{u.name} <span className={`chip ${u.category}`}>{CAT_WORD[u.category]}</span></div>
          <span className="tm">{fmt(u.minutes)}</span>
          <span className="share"><i className={FILL[u.category]} style={{ width: `${(u.minutes / max) * 100}%` }} /></span>
        </div>
      ))}
    </div>
  );
}

function Today({ data, reload }: { data: Data; reload: () => void }) {
  const t = totals(data.usage), tracked = t.good + t.bad, share = tracked ? Math.round((t.good / tracked) * 100) : 0;
  const over = data.usage.filter(u => u.overLimit);
  const headline = !tracked ? 'Nothing on your habits yet today.'
    : over.length ? `${share}% of your time went to good habits. ${over[0].name} is over its limit.`
    : share >= 70 ? `${share}% of your time went to good habits. A strong day so far.`
    : `${share}% of your time went to good habits.`;
  return (
    <>
      <NowCard orb={data.orb} reload={reload} />
      <section className="panel" aria-label="Today's balance">
        <h2 className="sec-title">Today <span>all devices</span></h2>
        <div className="hero">
          <div><div className="n good">{fmt(t.good)}</div><div className="k">on good habits</div></div>
          <div><div className="n bad">{fmt(t.bad)}</div><div className="k">on habits to reduce</div></div>
        </div>
        <Balance t={t} />
        <div className="headline">{headline}</div>
      </section>
      <section className="panel" aria-label="Your day">
        <h2 className="sec-title">Your day <span>hover a block for details</span></h2>
        <Timeline rows={data.timeline} />
      </section>
      <div className="cols">
        <section className="panel" aria-label="Daily limits"><h2 className="sec-title">Daily limits</h2><Limits rules={data.rules} usage={data.usage} /></section>
        <section className="panel" aria-label="Where your time went"><h2 className="sec-title">Where it went</h2><Breakdown usage={data.usage} /></section>
      </div>
    </>
  );
}

// ---- Week ----------------------------------------------------------------------------
function Week({ week }: { week: DayTotals[] }) {
  const [table, setTable] = useState(false);
  const max = Math.max(60, ...week.map(d => d.good + d.bad)), top = Math.ceil(max / 120) * 120;
  const good = week.reduce((s, d) => s + d.good, 0), bad = week.reduce((s, d) => s + d.bad, 0);
  const tracked = week.filter(d => d.good + d.bad > 0);
  const best = tracked.slice().sort((a, b) => b.good - b.bad - (a.good - a.bad))[0];
  return (
    <section className="panel" aria-label="Last 7 days">
      <h2 className="sec-title">Last 7 days <button className="btn sm" onClick={() => setTable(!table)}>{table ? 'Show chart' : 'Show table'}</button></h2>
      <div className="hero">
        <div><div className="n good">{fmt(good)}</div><div className="k">on good habits</div></div>
        <div><div className="n bad">{fmt(bad)}</div><div className="k">on habits to reduce</div></div>
      </div>
      <div className="legend"><span><i className="f-good" />Good</span><span><i className="f-bad" />To reduce</span></div>
      {table ? (
        <table className="tbl">
          <thead><tr><th>Day</th><th>Good</th><th>To reduce</th><th>Other</th></tr></thead>
          <tbody>{week.map(d => <tr key={d.label}><td>{d.label}</td><td>{fmt(d.good)}</td><td>{fmt(d.bad)}</td><td>{fmt(d.other)}</td></tr>)}</tbody>
        </table>
      ) : (
        <div className="week" role="img" aria-label="Good and reduce time for each of the last 7 days">
          <div className="yaxis">{[0, 0.5, 1].map(f => <span key={f} style={{ top: `${(1 - f) * 100}%` }}>{fmt(top * f)}</span>)}</div>
          <div className="plot">
            {[0, 0.5, 1].map(f => <div key={f} className="grid" style={{ bottom: `${f * 100}%` }} />)}
            {week.map(d => (
              <div key={d.label} className={`col${d.today ? ' today' : ''}`} data-tip={`${d.label}\nGood ${fmt(d.good)} · To reduce ${fmt(d.bad)}`}>
                {d.bad > 0 && <i className="f-bad" style={{ height: `${(d.bad / top) * 100}%` }} />}
                {d.good > 0 && <i className="f-good" style={{ height: `${(d.good / top) * 100}%` }} />}
              </div>
            ))}
          </div>
          <div className="days">{week.map(d => <span key={d.label}>{d.label}</span>)}</div>
        </div>
      )}
      <div className="headline">
        {best ? `Your best day was ${best.today ? 'today' : best.label}: ${fmt(best.good)} on good habits and ${fmt(best.bad)} to reduce.` : 'Your week fills in as Sapphier tracks your days.'}
      </div>
    </section>
  );
}

// ---- Habits --------------------------------------------------------------------------

function Habits({ data, reload }: { data: Data; reload: () => void }) {
  const [busy, setBusy] = useState(false);
  const save = async (r: Rule) => { setBusy(true); try { await saveRule({ ...r, updatedAt: Date.now() }); await reload(); } finally { setBusy(false); } };

  // apps used today that no habit covers, with their minutes
  const unsorted = useMemo(() => {
    const acc = new Map<string, { appId: string; appName: string; platform: 'mac' | 'android'; minutes: number }>();
    for (const r of data.timeline) {
      if (r.ruleId) continue;
      const u = acc.get(r.appId) ?? { appId: r.appId, appName: r.appName, platform: r.platform, minutes: 0 };
      u.minutes += (r.end - r.start) / 60000;
      acc.set(r.appId, u);
    }
    return [...acc.values()].sort((a, b) => b.minutes - a.minutes);
  }, [data.timeline]);

  const sort = (u: (typeof unsorted)[number], category: Category) => {
    const id = u.appName.toLowerCase().replace(/[^a-z0-9]+/g, '-');
    const existing = data.rules.find(r => r.id === id);
    const base: Rule = existing ?? { id, name: u.appName, category, dailyLimitMin: category === 'bad' ? 30 : null, mac: [], android: [], updatedAt: 0, deleted: false };
    void save({ ...base, category, deleted: false, [u.platform]: [...new Set([...base[u.platform], u.appId])] });
  };

  const rules = data.rules.filter(r => !r.deleted);
  return (
    <>
      {unsorted.length > 0 && (
        <section className="panel" aria-label="Apps to sort">
          <h2 className="sec-title">Sort these apps <span>{unsorted.length} not categorised</span></h2>
          <div className="unsorted">
            {unsorted.map(u => (
              <div className="u" key={u.appId}>
                <span className="ic c-neutral">{u.appName.slice(0, 1).toUpperCase()}</span>
                <span className="nm">{u.appName} <span className="muted mono">{fmt(u.minutes)} today</span></span>
                <button className="btn sm" disabled={busy} onClick={() => sort(u, 'good')}>Good</button>
                <button className="btn sm" disabled={busy} onClick={() => sort(u, 'bad')}>Reduce</button>
              </div>
            ))}
          </div>
        </section>
      )}
      <section className="panel" aria-label="Your habits">
        <h2 className="sec-title">Your habits <span>changes sync to your other devices</span></h2>
        <div className="habits">
          {rules.map(r => {
            const apps = [...r.mac, ...r.android];
            const field = r.category === 'bad' ? 'Daily limit' : r.category === 'good' ? 'Daily goal' : null;
            const step = (d: number) => {
              const v = Math.max(0, (r.dailyLimitMin ?? 0) + d);
              void save({ ...r, dailyLimitMin: v || null });
            };
            return (
              <div className="hcard" key={r.id}>
                <div className="row-between">
                  <span className="nm">{r.name}</span>
                  <span className="choice" role="group" aria-label={`${r.name} category`}>
                    {(['good', 'bad', 'neutral'] as Category[]).map(c => (
                      <button key={c} className={c} aria-pressed={r.category === c} disabled={busy}
                        onClick={() => r.category !== c && void save({ ...r, category: c, dailyLimitMin: c === 'bad' && r.dailyLimitMin == null ? 30 : r.dailyLimitMin })}>
                        {{ good: 'Good', bad: 'Reduce', neutral: 'Neutral' }[c]}
                      </button>
                    ))}
                  </span>
                </div>
                {apps.length > 0 && <div className="apps">{[...new Set(apps.map(appLabel))].map(a => <span key={a}>{a}</span>)}</div>}
                {field && (
                  <div className="row-between small">
                    <span>{field}</span>
                    <span className="stepper">
                      <button aria-label={`Less ${field.toLowerCase()}`} disabled={busy} onClick={() => step(-15)}>−</button>
                      <output>{r.dailyLimitMin ? fmt(r.dailyLimitMin) : 'None'}</output>
                      <button aria-label={`More ${field.toLowerCase()}`} disabled={busy} onClick={() => step(15)}>+</button>
                    </span>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </section>
    </>
  );
}

// ---- Devices -------------------------------------------------------------------------
function Devices({ data, reload }: { data: Data; reload: () => void }) {
  const s = data.sync;
  const [form, setForm] = useState({ url: s?.url ?? '', anonKey: s?.anonKey ?? '', email: s?.email ?? '', password: '' });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const run = async (f: () => Promise<unknown>) => {
    setBusy(true); setErr(null);
    try { await f(); setForm(v => ({ ...v, password: '' })); await reload(); } catch (e) { setErr(String(e)); } finally { setBusy(false); }
  };
  return (
    <>
      <section className="panel" aria-label="Your devices">
        <h2 className="sec-title">Your devices <span>today</span></h2>
        <div className="devices">
          {data.devices.map(d => (
            <div className="dev" key={d.device}>
              <div className="hd">
                <Icon name={d.platform === 'mac' ? 'laptop' : 'phone'} />
                <div>
                  <div className="nm">{d.platform === 'mac' ? 'Mac' : 'Android phone'} {d.thisDevice && <span className="chip accent">This device</span>}</div>
                  <div className="st"><span className="sync-dot" />{d.thisDevice ? 'Tracking' : `Last active ${clockTime(d.lastSeen)}`}</div>
                </div>
              </div>
              <div className="nums">
                <div><b className="good">{fmt(d.good)}</b><span>good</span></div>
                <div><b className="bad">{fmt(d.bad)}</b><span>to reduce</span></div>
                <div><b>{fmt(d.other)}</b><span>other</span></div>
              </div>
            </div>
          ))}
          {data.devices.length < 2 && <div className="dev ghost"><div className="nm">Add your phone</div><p className="note">Install Sapphier on Android and sign in with the same details below. Its day shows up here.</p></div>}
        </div>
      </section>
      <section className="panel" aria-label="Sync">
        <h2 className="sec-title">Sync <span>every 30 minutes and when you open Sapphier</span></h2>
        {s?.signedIn ? (
          <>
            <dl className="kv"><dt>Account</dt><dd>{s.email}</dd><dt>Project</dt><dd>{s.url.replace(/^https?:\/\//, '')}</dd><dt>Last sync</dt><dd>{s.lastSyncAt ? clockTime(s.lastSyncAt) : 'Not yet'}</dd></dl>
            <div><button className="btn primary sm" disabled={busy} onClick={() => run(syncNow)}><Icon name="sync" />{busy ? 'Syncing…' : 'Sync now'}</button></div>
          </>
        ) : (
          <form className="form" onSubmit={e => { e.preventDefault(); void run(() => signIn(form.url, form.anonKey, form.email, form.password)); }}>
            <p className="note">Your devices sync through your own free Supabase project. Create one, run <code>sync/schema.sql</code> in its SQL editor, add a user under Authentication, then sign in here and on your phone with the same details.</p>
            <label>Project URL<input id="sync-url" value={form.url} placeholder="https://your-project.supabase.co" onChange={e => setForm({ ...form, url: e.target.value })} /></label>
            <label>Anon key<input id="sync-key" value={form.anonKey} onChange={e => setForm({ ...form, anonKey: e.target.value })} /></label>
            <label>Email<input id="sync-email" type="email" value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>
            <label>Password<input id="sync-password" type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} /></label>
            <div><button className="btn primary sm" type="submit" disabled={busy || !form.url || !form.password}>{busy ? 'Signing in…' : 'Sign in'}</button></div>
          </form>
        )}
        {(err || s?.error) && <p className="error">{err ?? s?.error}</p>}
        <p className="note">Each device keeps its own record on the device and shares only what is new. Habit changes sync too; the newest change wins.</p>
      </section>
    </>
  );
}
