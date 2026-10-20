import './orb.css';

export type OrbState = 'good' | 'bad' | 'neutral' | 'idle';

/** A point on a wall (screen edge or corner) and the direction into the screen. */
export interface Anchor { x: number; y: number; nx: -1 | 0 | 1; ny: -1 | 0 | 1 }
export interface Bounds { l: number; t: number; r: number; b: number }

export interface WallOrbOptions {
  R: number;
  bounds: Bounds;
  anchor: Anchor;
  onClick?: () => void;
  onMenu?: () => void;
  /** Hovered (true) or no longer hovered (false). */
  onHover?: (on: boolean) => void;
  /** Pressed: the host can grow its window to the whole screen for the drag. */
  onPressStart?: () => void;
  /** It flowed to a new edge or corner. */
  onMove?: (a: Anchor) => void;
  /** Back at rest after being handled: the host can shrink its window again (to the
   *  hover size while the pointer is still on it). */
  onSettled?: (hovered: boolean) => void;
}

export const GLYPH: Record<OrbState, string> = {
  good: 'M7 12.5l3 3 7-7',
  bad: 'M12 6.5v7M12 17.5v.01',
  neutral: 'M7 12h10',
  idle: 'M9 12h.01M15 12h.01',
};

const reduceMotion = () => !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
const clamp = (v: number, a: number, b: number) => Math.min(Math.max(v, a), b);
let seq = 0;

/**
 * The orb as liquid stuck to the wall: its centre rests on a screen edge (or in a corner)
 * and the window edge clips it, so it shows as a half circle (a quarter in a corner).
 * Hovered, droplets drift around its rim and it breathes; held, it swells towards the
 * long-press; pulled off, it becomes a ball on a neck of liquid and flows to the nearest
 * edge or corner when let go. At rest it is flat shapes: the goo filter (blur + alpha
 * threshold) and the animation frames run only while it is handled.
 *
 * Coordinates are in the host layer's pixels; pointer maths uses screen coordinates so the
 * host window can move or resize mid-drag (see setOrigin).
 */
export class WallOrb {
  a: Anchor;
  bounds: Bounds;
  readonly R: number;
  private o: WallOrbOptions;
  private m = { x: 0, y: 0, vx: 0, vy: 0 };
  private r = {
    on: false, moved: false, spring: false, hover: false, suppress: false, longFired: false,
    raf: 0, anim: 0, sleep: 0, long: 0, jt: 0, sx: 0, sy: 0, lx: 0, ly: 0, t: 0, pressAt: 0,
  };
  private state: OrbState = 'idle';
  private hadState = false;
  private press = false;
  private live = false;
  private hv = 0;
  private phase = 0;
  private hold = 0;
  private goo: HTMLElement;
  private hit: HTMLButtonElement;
  private glyph: SVGSVGElement;
  private path: SVGPathElement;
  private pool: HTMLElement;
  private neck: HTMLElement[];
  private buds: HTMLElement[];
  private ball: HTMLElement;
  private ballFill: HTMLElement;
  private filter: string;

  constructor(layer: HTMLElement, o: WallOrbOptions) {
    this.o = o;
    this.R = o.R;
    this.a = o.anchor;
    this.bounds = o.bounds;
    const R = o.R, id = 'orbgoo' + ++seq;
    layer.innerHTML = `
      <svg width="0" height="0" style="position:absolute" aria-hidden="true"><filter id="${id}" x="0" y="0" width="100%" height="100%">
        <feGaussianBlur in="SourceGraphic" stdDeviation="${R * 0.2}" result="b"/>
        <feColorMatrix in="b" mode="matrix" values="1 0 0 0 0  0 1 0 0 0  0 0 1 0 0  0 0 0 22 -9" result="g"/>
        <feComposite in="SourceGraphic" in2="g" operator="atop"/></filter></svg>
      <div class="orb-goo g-idle" aria-hidden="true">
        <span></span><span></span><span></span><span></span>
        <span class="orb-bud"></span><span class="orb-bud"></span><span class="orb-bud"></span><span class="orb-ball orb-ease"><i></i></span>
      </div>
      <button type="button" class="orb-hit h-idle" style="width:${R * 2}px;height:${R * 2}px">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>`;
    this.goo = layer.querySelector('.orb-goo')!;
    this.hit = layer.querySelector('.orb-hit')!;
    this.glyph = this.hit.querySelector('svg')!;
    this.path = this.hit.querySelector('path')!;
    this.filter = `url(#${id}) drop-shadow(var(--shadow-orb))`;
    const k = Array.from(this.goo.children) as HTMLElement[];
    this.pool = k[0];
    this.neck = [k[1], k[2], k[3]];
    this.buds = [k[4], k[5], k[6]];
    this.ball = k[7];
    this.ballFill = k[7].firstElementChild as HTMLElement;
    this.path.setAttribute('d', GLYPH.idle);
    this.bind();
    this.render();
  }

  private get corner() { return this.a.nx !== 0 && this.a.ny !== 0; }
  get busy() { return this.r.on || this.r.spring || this.r.hover || !!this.r.anim; }

  /** The host window moved by (dx, dy) screen pixels: keep everything where it is on screen. */
  setOrigin(dx: number, dy: number) {
    this.a = { ...this.a, x: this.a.x - dx, y: this.a.y - dy };
    const b = this.bounds;
    this.bounds = { l: b.l - dx, t: b.t - dy, r: b.r - dx, b: b.b - dy };
    this.render();
  }

  setState(s: OrbState, label?: string) {
    this.hit.setAttribute('aria-label', label || s);
    if (s === this.state) return;
    const had = this.hadState;
    this.state = s;
    this.hadState = true;
    this.goo.className = 'orb-goo g-' + s + (this.live ? ' is-live' : '');
    this.hit.className = 'orb-hit h-' + s;
    this.path.setAttribute('d', GLYPH[s]);
    if (s === 'bad' && had) { void this.hit.offsetWidth; this.hit.classList.add('pulse'); }
    if (had && !reduceMotion()) {
      this.wake();
      this.ballFill.classList.remove('orb-jelly');
      void this.ballFill.offsetWidth;
      this.ballFill.classList.add('orb-jelly');
      clearTimeout(this.r.jt);
      this.r.jt = window.setTimeout(() => {
        this.ballFill.classList.remove('orb-jelly');
        if (!this.busy) this.sleepSoon(0);
      }, 640);
    }
  }

  private setLive(on: boolean) {
    this.live = on;
    this.goo.classList.toggle('is-live', on);
    this.goo.style.filter = on ? this.filter : 'none';
  }
  private wake() { clearTimeout(this.r.sleep); if (!reduceMotion()) this.setLive(true); }
  private sleepSoon(ms = 420) { clearTimeout(this.r.sleep); this.r.sleep = window.setTimeout(() => this.setLive(false), ms); }

  /** Back at rest after a press, a drag or a hover: let the host shrink its window, and
   *  switch the filter off once nothing is moving. */
  private rested() {
    const r = this.r;
    if (r.on || r.spring) return;
    this.o.onSettled?.(r.hover);
    if (!r.hover && !r.anim) this.sleepSoon(120);
  }

  /** The nearest edge or corner to a point, as an anchor. */
  private snap(px: number, py: number): Anchor {
    const { l, t, r, b } = this.bounds, R = this.R, zone = R * 2.6;
    const left = px - l < r - px, top = py - t < b - py;
    const hd = left ? px - l : r - px, vd = top ? py - t : b - py;
    const ex = left ? l : r, ey = top ? t : b, nx = left ? 1 : -1, ny = top ? 1 : -1;
    if (hd < zone && vd < zone) return { x: ex, y: ey, nx, ny };
    if (hd <= vd) return { x: ex, y: clamp(py, t + R, b - R), nx, ny: 0 };
    return { x: clamp(px, l + R, r - R), y: ey, nx: 0, ny };
  }

  private settle() {
    const r = this.r, m = this.m;
    if (reduceMotion()) { Object.assign(m, { x: 0, y: 0, vx: 0, vy: 0 }); this.render(); this.rested(); return; }
    r.spring = true;
    const step = () => {
      m.vx = (m.vx - m.x * 0.12) * 0.82;
      m.vy = (m.vy - m.y * 0.12) * 0.82;
      m.x += m.vx;
      m.y += m.vy;
      if (Math.abs(m.x) + Math.abs(m.y) + Math.abs(m.vx) + Math.abs(m.vy) < 0.4) {
        Object.assign(m, { x: 0, y: 0, vx: 0, vy: 0 });
        r.spring = false;
        this.render();
        this.rested();
        return;
      }
      this.render();
      r.raf = requestAnimationFrame(step);
    };
    r.raf = requestAnimationFrame(step);
  }

  /** Keeps the liquid moving while it is hovered or held, then eases to rest and stops. */
  private animate() {
    if (this.r.anim || reduceMotion()) { this.render(); return; }
    const r = this.r;
    let last = performance.now();
    const step = (now: number) => {
      const dt = Math.min((now - last) / 1000, 0.05);
      last = now;
      const engaged = (r.hover || this.press) && !(r.on && r.moved);
      this.hv += ((engaged ? 1 : 0) - this.hv) * Math.min(dt * 9, 1);
      // a hold winds up towards the long-press, stays full while held and relaxes gently
      const holdTarget = this.press && !r.moved ? clamp((now - r.pressAt) / 550, 0, 1) : 0;
      this.hold += (holdTarget - this.hold) * Math.min(dt * (holdTarget > this.hold ? 10 : 4), 1);
      this.phase += dt; // one steady clock: changing a wave's speed mid-flight makes it jump
      this.render();
      if (engaged || this.hv > 0.01 || this.hold > 0.01) r.anim = requestAnimationFrame(step);
      else {
        r.anim = 0;
        this.hv = 0;
        this.hold = 0;
        this.render();
        this.rested();
      }
    };
    r.anim = requestAnimationFrame(step);
  }

  private bind() {
    const r = this.r, m = this.m, h = this.hit;
    h.addEventListener('pointerenter', () => {
      r.hover = true;
      this.wake();
      this.animate();
      if (!r.on) this.o.onHover?.(true);
    });
    h.addEventListener('pointerleave', () => {
      r.hover = false;
      this.render();
      this.o.onHover?.(false);
      if (!r.on && !r.spring && !r.anim) this.rested();
    });
    h.addEventListener('pointerdown', e => {
      if (e.button) return;
      h.setPointerCapture(e.pointerId);
      this.o.onHover?.(false);
      cancelAnimationFrame(r.raf);
      r.spring = false;
      r.on = true;
      r.moved = false;
      r.longFired = false;
      r.sx = e.screenX - m.x; r.sy = e.screenY - m.y;
      r.lx = e.screenX; r.ly = e.screenY;
      r.t = r.pressAt = performance.now();
      this.press = true;
      this.wake();
      this.animate();
      this.o.onPressStart?.();
      clearTimeout(r.long);
      r.long = window.setTimeout(() => {
        if (r.on && !r.moved) { r.longFired = true; this.o.onMenu?.(); }
      }, 550);
    });
    h.addEventListener('pointermove', e => {
      if (!r.on) return;
      const x = e.screenX - r.sx, y = e.screenY - r.sy;
      if (!r.moved && Math.hypot(x - m.x, y - m.y) < 4) return;
      clearTimeout(r.long);
      const now = performance.now(), dt = Math.max(now - r.t, 8);
      r.moved = true;
      m.vx = ((e.screenX - r.lx) / dt) * 16;
      m.vy = ((e.screenY - r.ly) / dt) * 16;
      r.lx = e.screenX; r.ly = e.screenY; r.t = now;
      // keep the ball's centre on the screen
      const { l, t, r: rr, b } = this.bounds;
      m.x = clamp(this.a.x + x, l, rr) - this.a.x;
      m.y = clamp(this.a.y + y, t, b) - this.a.y;
      this.render();
    });
    const end = (ok: boolean) => {
      if (!r.on) return;
      r.on = false;
      clearTimeout(r.long);
      r.suppress = r.moved || r.longFired;
      this.press = false;
      if (ok && r.moved) {
        // flow to the nearest edge or corner; the ball keeps its place and springs there
        const px = this.a.x + m.x, py = this.a.y + m.y, next = this.snap(px, py);
        this.a = next;
        m.x = px - next.x;
        m.y = py - next.y;
        this.o.onMove?.(next);
      }
      this.settle();
    };
    h.addEventListener('pointerup', () => end(true));
    h.addEventListener('pointercancel', () => end(false));
    h.addEventListener('click', () => {
      if (r.suppress) { r.suppress = false; return; }
      this.o.onClick?.();
    });
    h.addEventListener('contextmenu', e => {
      e.preventDefault();
      if (!r.longFired) this.o.onMenu?.();
    });
  }

  render() {
    const R = this.R, a = this.a, { x, y, vx, vy } = this.m, r = this.r;
    const bx = a.x + x, by = a.y + y, dist = Math.hypot(x, y), speed = Math.hypot(vx, vy);
    const place = (el: HTMLElement, X: number, Y: number, rad: number, extra = '') => {
      el.style.width = el.style.height = rad * 2 + 'px';
      el.style.transform = `translate(${X - rad}px,${Y - rad}px)${extra}`;
    };
    // inward direction, unit length (diagonal in a corner)
    const nl = Math.hypot(a.nx, a.ny), ux = a.nx / nl, uy = a.ny / nl;
    const detach = clamp(dist / (R * 1.2), 0, 1);

    // liquid left on the wall, and the neck that joins it to the ball, thinning until it snaps
    const f = Math.min(dist / (R * 4), 1);
    this.pool.style.display = dist > 2 ? '' : 'none';
    if (dist > 2) place(this.pool, a.x, a.y, R * 0.85 * (1 - f * 0.7));
    [0.3, 0.55, 0.8].forEach((t, i) => {
      const k = dist > 2 ? (1 - f * f) * (0.95 - 0.35 * Math.abs(t - 0.5)) : 0;
      this.neck[i].style.display = k > 0.02 ? '' : 'none';
      if (k > 0.02) place(this.neck[i], a.x + x * t, a.y + y * t, R * 0.55 * k);
    });

    // hovered or held: three droplets drift, swell and shrink around the rim, joined by the goo
    const ang0 = Math.atan2(uy, ux), hv = dist < 2 ? this.hv : 0, ph = this.phase, hold = this.hold;
    const animating = !!r.anim;
    ([[-0.62, 0.34], [0.66, 0.28], [0.02, 0.22]] as const).forEach(([da, s], i) => {
      this.buds[i].classList.toggle('orb-bud', !animating);
      const calm = 1 - hold; // while held, the droplets stop drifting and gather into the ball
      const ang = ang0 + da + 0.3 * calm * Math.sin(ph * (1.5 + i * 0.35) + i * 2.1);
      const d = R * (0.92 + 0.12 * calm * Math.sin(ph * 2.2 + i * 1.3) - hold * 0.45) * hv;
      const rad = R * s * (0.85 + 0.22 * calm * Math.sin(ph * 2 + i * 1.9) + hold * 0.25) * (0.4 + 0.6 * hv);
      place(this.buds[i], bx + Math.cos(ang) * d, by + Math.sin(ang) * d, rad);
    });

    // the ball: stretched along its motion; breathing when hovered; swelling evenly when held
    let deform = '';
    if (dist > 1 || speed > 0.5) {
      const ax = speed > 0.5 ? vx : x, ay = speed > 0.5 ? vy : y, ang = (Math.atan2(ay, ax) * 180) / Math.PI;
      const st = 1 + Math.min((dist / R) * 0.1 + (speed / R) * 0.5, 0.32);
      deform = ` rotate(${ang}deg) scale(${st},${1 / st}) rotate(${-ang}deg)`;
    } else if (hv > 0.01 || hold > 0.01) {
      const calm = 1 - hold, eased = hold * hold * (3 - 2 * hold);
      const breathe = 1 + 0.025 * Math.sin(ph * 2.3) * hv * calm + eased * 0.12;
      const w = 0.035 * hv * calm * Math.sin(ph * 3.4);
      const rot = (ph * 24) % 360;
      deform = ` scale(${breathe.toFixed(4)}) rotate(${rot.toFixed(2)}deg) scale(${(1 + w).toFixed(4)},${(1 - w).toFixed(4)}) rotate(${(-rot).toFixed(2)}deg)`;
    }
    this.ball.classList.toggle('orb-ease', !((r.on && r.moved) || r.spring || animating));
    place(this.ball, bx, by, R, deform);

    // the hit area follows the ball; the glyph sits in the middle of the visible part
    // (4R/3π from the centre along each inward axis) and moves to the centre once free
    this.hit.style.transform = `translate(${bx - R}px,${by - R}px)`;
    const c = ((4 * R) / (3 * Math.PI)) * (1 - detach);
    const g0 = this.corner ? R * 0.42 : R * 0.5;
    const g = g0 + (R * 0.62 - g0) * detach;
    this.glyph.style.width = this.glyph.style.height = g + 'px';
    this.glyph.style.transform = `translate(calc(-50% + ${a.nx * c}px), calc(-50% + ${a.ny * c}px))`;
  }
}
