import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { LiquidOrb } from './orb/LiquidOrb';
import { getOrbState, moveOrb, onOrbState, openMain, setOrbExpanded, showOrbMenu, type OrbUpdate } from './bridge';
import './tokens.css';
import './orb-page.css';

const SIZE = 44;

function title(u: OrbUpdate): string {
  if (u.state === 'idle' || !u.current) return 'Idle';
  const what = u.current.ruleName ?? u.current.appName;
  return u.state === 'good' ? `${what}: keep going` : u.state === 'bad' ? `${what}: ease off` : what;
}

function OrbPage() {
  const [u, setU] = useState<OrbUpdate>({ state: 'idle', current: null });

  useEffect(() => {
    let off: (() => void) | undefined;
    getOrbState().then(setU);
    onOrbState(setU).then((f) => (off = f));
    return () => off?.();
  }, []);

  return (
    <div className="orb-dock" title={title(u)}>
      <LiquidOrb
        state={u.state}
        size={SIZE}
        docked="right"
        label={title(u)}
        onClick={() => void openMain()}
        onContextMenu={() => void showOrbMenu()}
        // the window is small at rest so it never blocks clicks; it grows while the orb is handled
        onPressStart={() => void setOrbExpanded(true)}
        onSettled={() => void setOrbExpanded(false)}
        onDragEnd={(_dx, dy) => {
          // a vertical pull moves the orb along the screen edge; sideways pulls spring back
          if (Math.abs(dy) < SIZE * 0.6) return;
          void moveOrb(dy);
          return { dx: 0, dy };
        }}
      />
    </div>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <OrbPage />
  </StrictMode>,
);
