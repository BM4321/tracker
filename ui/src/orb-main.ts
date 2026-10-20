import { WallOrb, type Anchor } from './orb/WallOrb';
import { getOrbState, getToday, inTauri, onOrbState, openMain, orbAnchor, orbPlace, orbScreen, setOrbAnchor, showOrbMenu, type OrbUpdate, type Rect } from './bridge';
import { formatMinutes } from './format';
import './tokens.css';
import './orb-page.css';

// The orb window holds only the orb. Its frame depends on what you are doing with it:
//   rest   a small square around the orb, clipped by the screen edge, so it blocks no clicks
//   hover  a larger square, with room for the quick-look bubble
//   full   the whole screen while you hold or drag it, so it can flow to any edge
const R = 30;
const REST = R * 3;
const HOVER = 560;
type Mode = 'rest' | 'hover' | 'full';

interface Saved { fx: number; fy: number; nx: -1 | 0 | 1; ny: -1 | 0 | 1 }

const layer = document.getElementById('layer')!;
const tip = document.getElementById('tip')!;
let screen: Rect;
let frame: Rect;
let mode: Mode = 'rest';
let orb: WallOrb;
let update: OrbUpdate = { state: 'idle', current: null, pausedUntil: null };

/** The anchor in screen points. */
const anchorOnScreen = (): Anchor => ({ ...orb.a, x: orb.a.x + frame.x, y: orb.a.y + frame.y });

function rectFor(m: Mode, a: Anchor): Rect {
  // in a browser (npm run dev) the page cannot move its window: use all of it
  if (m === 'full' || !inTauri) return screen;
  const half = (m === 'rest' ? REST : HOVER) / 2;
  const l = Math.max(screen.x, a.x - half), t = Math.max(screen.y, a.y - half);
  const r = Math.min(screen.x + screen.w, a.x + half), b = Math.min(screen.y + screen.h, a.y + half);
  return { x: l, y: t, w: r - l, h: b - t };
}

let placing = Promise.resolve();
function setMode(m: Mode) {
  // queued, so quick hover/press changes apply in order
  placing = placing.then(async () => {
    const next = rectFor(m, anchorOnScreen());
    mode = m;
    if (next.x === frame.x && next.y === frame.y && next.w === frame.w && next.h === frame.h) return;
    await orbPlace(next);
    orb.setOrigin(next.x - frame.x, next.y - frame.y);
    frame = next;
    if (tip.classList.contains('on')) placeTip();
  }).catch(() => {});
}

function label(u: OrbUpdate) {
  if (u.state === 'idle' || !u.current) return u.pausedUntil ? 'Paused' : 'Idle';
  return u.current.ruleName ?? u.current.appName;
}

function placeTip() {
  const a = orb.a, w = tip.offsetWidth, h = tip.offsetHeight, gap = R + 12;
  const x = a.nx < 0 ? a.x - gap - w : a.nx > 0 ? a.x + gap : a.x - w / 2;
  const y = a.ny < 0 ? a.y - gap - h : a.ny > 0 ? a.y + gap : a.y - h / 2;
  tip.style.left = Math.min(Math.max(x, 8), frame.w - w - 8) + 'px';
  tip.style.top = Math.min(Math.max(y, 8), frame.h - h - 8) + 'px';
}

async function showTip() {
  const u = update, name = label(u);
  let line = 'Click to open Sapphier';
  if (u.current) {
    const key = u.current.ruleId ?? u.current.appId;
    const today = (await getToday('all')).find(x => x.key === key);
    line = `${formatMinutes(today?.minutes ?? 0)} today · click to open`;
  } else if (u.pausedUntil) {
    line = `Until ${new Date(u.pausedUntil).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} · right-click to resume`;
  }
  tip.innerHTML = '';
  const b = document.createElement('b');
  b.textContent = name;
  tip.append(b, document.createTextNode(line));
  tip.classList.add('on');
  placeTip();
}

function hideTip() { tip.classList.remove('on'); }

function applyUpdate(u: OrbUpdate) {
  update = u;
  orb.setState(u.state, `${label(u)}. Click to open Sapphier`);
}

async function start() {
  screen = await orbScreen();
  // where it was left, as fractions of the screen; the right edge at first
  let saved: Saved = { fx: 1, fy: 0.4, nx: -1, ny: 0 };
  try { const s = await orbAnchor(); if (s) saved = JSON.parse(s); } catch { /* first run */ }
  const ax = saved.nx > 0 ? screen.x : saved.nx < 0 ? screen.x + screen.w : screen.x + saved.fx * screen.w;
  const ay = saved.ny > 0 ? screen.y : saved.ny < 0 ? screen.y + screen.h : screen.y + saved.fy * screen.h;
  const a: Anchor = {
    x: saved.nx ? ax : Math.min(Math.max(ax, screen.x + R), screen.x + screen.w - R),
    y: saved.ny ? ay : Math.min(Math.max(ay, screen.y + R), screen.y + screen.h - R),
    nx: saved.nx, ny: saved.ny,
  };
  frame = rectFor('rest', a);
  orb = new WallOrb(layer, {
    R,
    anchor: { ...a, x: a.x - frame.x, y: a.y - frame.y },
    bounds: { l: screen.x - frame.x, t: screen.y - frame.y, r: screen.x + screen.w - frame.x, b: screen.y + screen.h - frame.y },
    onClick: () => { hideTip(); void openMain(); },
    onMenu: () => { hideTip(); void showOrbMenu(); },
    onHover: on => {
      if (on && mode === 'rest') { setMode('hover'); void showTip(); }
      else if (on) void showTip();
      else hideTip();
    },
    onPressStart: () => setMode('full'),
    onMove: next => {
      const s = { ...next, x: next.x + frame.x, y: next.y + frame.y };
      const out: Saved = { fx: (s.x - screen.x) / screen.w, fy: (s.y - screen.y) / screen.h, nx: s.nx, ny: s.ny };
      void setOrbAnchor(JSON.stringify(out));
    },
    onSettled: hovered => setMode(hovered ? 'hover' : 'rest'),
  });
  await orbPlace(frame);
  applyUpdate(await getOrbState());
  await onOrbState(applyUpdate);
}

void start();
