import { useEffect, useId, useRef, useState, type CSSProperties, type MouseEvent, type PointerEvent } from 'react';
import './orb.css';

export type OrbState = 'good' | 'bad' | 'neutral' | 'idle';

const GLYPH: Record<OrbState, string> = {
  good: 'M7 12.5l3 3 7-7',
  bad: 'M12 6.5v7M12 17.5v.01',
  neutral: 'M7 12h10',
  idle: 'M9 12h.01M15 12h.01',
};
const LABEL: Record<OrbState, string> = {
  good: 'Good habit',
  bad: 'Habit to reduce',
  neutral: 'Uncategorised',
  idle: 'Idle',
};

const reduceMotion = () => !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(' ');

export interface LiquidOrbProps {
  state: OrbState;
  size?: number;
  docked?: 'left' | 'right';
  label?: string;
  onClick?: (e: MouseEvent) => void;
  /**
   * Called when a drag ends, with the ball's offset from rest. Return how much of it
   * the host absorbed (for example by moving the window); the rest springs back.
   */
  onDragEnd?: (dx: number, dy: number) => { dx: number; dy: number } | void;
  onContextMenu?: (e: MouseEvent) => void;
  /** Called on press, before any movement (the host can grow its window to make room). */
  onPressStart?: () => void;
  /** Called once the orb is back at rest after a press or drag. */
  onSettled?: () => void;
}

interface Motion { x: number; y: number; vx: number; vy: number }
const REST: Motion = { x: 0, y: 0, vx: 0, vy: 0 };

/**
 * The Orb design system's liquid orb. At rest it is flat shapes and costs nothing.
 * While touched, an SVG goo filter (blur + alpha threshold) melts the ball, tether and
 * droplets together and a requestAnimationFrame spring drives the wobble; both switch
 * off ~0.4s after it settles. Pointer maths uses screen coordinates so the host window
 * can resize or move mid-drag without the ball jumping.
 */
export function LiquidOrb({ state, size = 44, docked, label, onClick, onDragEnd, onContextMenu, onPressStart, onSettled }: LiquidOrbProps) {
  const id = 'orbgoo' + useId().replace(/[^a-zA-Z0-9]/g, '');
  const r = useRef({
    ...REST, on: false, moved: false, spring: false, hover: false, suppress: false,
    sx: 0, sy: 0, lx: 0, ly: 0, t: 0, raf: 0, sleep: 0, jt: 0, prev: '' as OrbState | '',
  }).current;
  const [m, setM] = useState<Motion>(REST);
  const [hover, setHover] = useState(false);
  const [press, setPress] = useState(false);
  const [live, setLive] = useState(false);
  const [jelly, setJelly] = useState(false);

  const wake = () => { clearTimeout(r.sleep); if (!reduceMotion()) setLive(true); };
  const sleepSoon = (ms = 420) => { clearTimeout(r.sleep); r.sleep = window.setTimeout(() => setLive(false), ms); };

  useEffect(() => () => { cancelAnimationFrame(r.raf); clearTimeout(r.sleep); clearTimeout(r.jt); }, []);
  useEffect(() => {
    if (r.prev && r.prev !== state && !reduceMotion()) {
      wake();
      setJelly(true);
      clearTimeout(r.jt);
      r.jt = window.setTimeout(() => { setJelly(false); if (!r.on && !r.hover && !r.spring) sleepSoon(0); }, 640);
    }
    r.prev = state;
  }, [state]);

  function settle() {
    if (reduceMotion()) { Object.assign(r, REST); setM(REST); onSettled?.(); return; }
    r.spring = true;
    const step = () => {
      r.vx = (r.vx - r.x * 0.12) * 0.82;
      r.vy = (r.vy - r.y * 0.12) * 0.82;
      r.x += r.vx;
      r.y += r.vy;
      if (Math.abs(r.x) + Math.abs(r.y) + Math.abs(r.vx) + Math.abs(r.vy) < 0.4) {
        Object.assign(r, REST);
        r.spring = false;
        setM(REST);
        if (!r.hover) sleepSoon(120);
        onSettled?.();
        return;
      }
      setM({ x: r.x, y: r.y, vx: r.vx, vy: r.vy });
      r.raf = requestAnimationFrame(step);
    };
    r.raf = requestAnimationFrame(step);
  }

  const handlers = {
    onPointerEnter: () => { r.hover = true; setHover(true); wake(); },
    onPointerLeave: () => { r.hover = false; setHover(false); if (!r.on && !r.spring) sleepSoon(); },
    onPointerDown: (e: PointerEvent<HTMLButtonElement>) => {
      if (e.button) return;
      e.currentTarget.setPointerCapture(e.pointerId);
      cancelAnimationFrame(r.raf);
      r.spring = false;
      r.on = true;
      r.moved = false;
      r.sx = e.screenX - r.x; r.sy = e.screenY - r.y;
      r.lx = e.screenX; r.ly = e.screenY; r.t = performance.now();
      setPress(true);
      wake();
      onPressStart?.();
    },
    onPointerMove: (e: PointerEvent<HTMLButtonElement>) => {
      if (!r.on) return;
      const x = e.screenX - r.sx, y = e.screenY - r.sy;
      if (!r.moved && Math.hypot(x - r.x, y - r.y) < 4) return;
      const now = performance.now(), dt = Math.max(now - r.t, 8);
      r.moved = true;
      r.vx = ((e.screenX - r.lx) / dt) * 16; r.vy = ((e.screenY - r.ly) / dt) * 16;
      r.lx = e.screenX; r.ly = e.screenY; r.t = now; r.x = x; r.y = y;
      setM({ x, y, vx: r.vx, vy: r.vy });
    },
    onPointerUp: () => {
      if (!r.on) return;
      r.on = false;
      r.suppress = r.moved;
      setPress(false);
      if (r.moved && onDragEnd) {
        const took = onDragEnd(r.x, r.y);
        if (took) { r.x -= took.dx; r.y -= took.dy; }
      }
      settle();
    },
    onPointerCancel: () => { if (!r.on) return; r.on = false; setPress(false); settle(); },
    onClick: (e: MouseEvent) => { if (r.suppress) { r.suppress = false; return; } onClick?.(e); },
    onContextMenu: (e: MouseEvent) => { if (onContextMenu) { e.preventDefault(); onContextMenu(e); } },
  };

  const pad = Math.round(size * 2.5), W = docked ? Math.round(size * 1.4) : size;
  const cxr = docked === 'right' ? W - size / 2 : size / 2, cy = size / 2, dir = docked === 'right' ? -1 : 1;
  const { x, y } = m, dist = Math.hypot(x, y), speed = Math.hypot(m.vx, m.vy);
  const at = (X: number, Y: number, d: number, extra = ''): CSSProperties => ({
    width: d, height: d, transform: `translate(${pad + X - d / 2}px,${pad + Y - d / 2}px)${extra}`,
  });

  // the ball stretches along its motion and squashes when pressed
  let deform = '';
  if (dist > 1 || speed > 0.5) {
    const ax = speed > 0.5 ? m.vx : x, ay = speed > 0.5 ? m.vy : y, ang = (Math.atan2(ay, ax) * 180) / Math.PI;
    const st = 1 + Math.min((dist / size) * 0.18 + (speed / size) * 0.6, 0.35);
    deform = ` rotate(${ang}deg) scale(${st},${1 / st}) rotate(${-ang}deg)`;
  } else if (press) deform = ' scale(1.1,0.9)';
  const moving = (r.on && r.moved) || r.spring;

  const goo = [];
  if (docked) {
    const th = size * 0.55, tl = docked === 'right' ? cxr : -8;
    goo.push(<span key="t" style={{ width: docked === 'right' ? W + 8 - cxr : cxr + 8, height: th, transform: `translate(${pad + tl}px,${pad + cy - th / 2}px)` }} />);
    // a neck of droplets that thins as the ball is pulled from the edge, then snaps
    if (dist > 2) {
      const f = Math.min(dist / (size * 2.6), 1);
      [0.28, 0.52, 0.76].forEach((t, i) => {
        const k = (1 - f * f) * (0.9 - 0.3 * Math.abs(t - 0.5));
        if (k > 0.02) goo.push(<span key={'n' + i} style={at(cxr + x * t, cy + y * t, size * 0.72 * k)} />);
      });
    }
  }
  const budOut = hover && !press && dist < 2 && !reduceMotion();
  [[0.52, -0.22, 0.34], [0.42, 0.3, 0.26]].forEach((b, i) => {
    const bx = budOut ? cxr + dir * size * b[0] : cxr, by = budOut ? cy + size * b[1] : cy;
    goo.push(<span key={'b' + i} className="orb-bud" style={at(bx + x, by + y, size * b[2], budOut ? '' : ' scale(0.4)')} />);
  });
  goo.push(
    <span key="o" className={cx('orb-ball', !moving && 'orb-ease')} style={at(cxr + x, cy + y, size, deform)}>
      <i className={jelly ? 'orb-jelly' : ''} />
    </span>,
  );

  return (
    <span className={cx('orb-wrap', docked && 'dock-' + docked)} style={{ width: W, height: size }}>
      <svg width={0} height={0} style={{ position: 'absolute' }} aria-hidden>
        <filter id={id} x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur in="SourceGraphic" stdDeviation={Math.max(3, size * 0.14)} result="b" />
          <feColorMatrix in="b" mode="matrix" values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 22 -9" result="g" />
          <feComposite in="SourceGraphic" in2="g" operator="atop" />
        </filter>
      </svg>
      <span
        className={cx('orb-goo', 'g-' + state, live && 'is-live')}
        aria-hidden
        style={{ left: -pad, top: -pad, width: W + pad * 2, height: size + pad * 2, filter: live ? `url(#${id}) drop-shadow(var(--shadow-orb))` : 'none' }}
      >
        {goo}
      </span>
      <button
        type="button"
        className={cx('orb-hit', 'h-' + state)}
        aria-label={label ?? LABEL[state]}
        style={{ width: size, height: size, transform: `translate(${cxr + x - size / 2}px,${cy + y - size / 2}px)` }}
        {...handlers}
      >
        <svg viewBox="0 0 24 24" width={Math.round(size * 0.45)} height={Math.round(size * 0.45)} aria-hidden>
          <path d={GLYPH[state]} fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
    </span>
  );
}
