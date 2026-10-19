import type { ButtonHTMLAttributes, ReactNode } from 'react';
import type { Category } from '../bridge';
import './components.css';

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(' ');

/** `1h 12m`, `45m`, `<1m` — the Orb design system's time format. */
export function formatMinutes(m: number): string {
  if (m <= 0) return '0m';
  if (m < 1) return '<1m';
  const h = Math.floor(m / 60), mn = Math.round(m % 60);
  return h ? `${h}h${mn ? ` ${mn}m` : ''}` : `${mn}m`;
}

const LABEL: Record<Category, string> = { good: 'Good habit', bad: 'Habit to reduce', neutral: 'Uncategorised' };

export function StatusChip({ state, children }: { state: Category; children?: ReactNode }) {
  return <span className={cx('oc-chip', 'oc-chip-' + state)}>{children ?? LABEL[state]}</span>;
}

export interface ActivityRowProps {
  app: string;
  category?: string;
  minutes: number;
  goal?: number | null;
  state: Category;
  note?: string;
}

export function ActivityRow({ app, category, minutes, goal, state, note }: ActivityRowProps) {
  if (note == null && goal) {
    note = state === 'bad'
      ? minutes > goal ? `${formatMinutes(minutes - goal)} over ${formatMinutes(goal)}` : `${formatMinutes(goal - minutes)} left`
      : `goal ${formatMinutes(goal)}`;
  }
  const pct = goal ? Math.min(minutes / goal, 1) * 100 : 100;
  return (
    <div className={cx('oc-row', 'oc-row-' + state)}>
      <div>
        <div className="oc-row-name">{app}</div>
        <div className="oc-row-cat">{category}</div>
      </div>
      <div>
        <div className="oc-row-time">{formatMinutes(minutes)}</div>
        {note ? <div className="oc-row-note">{note}</div> : null}
      </div>
      <div className="oc-meter" role="presentation"><i style={{ width: pct + '%' }} /></div>
    </div>
  );
}

export function Button({ variant = 'quiet', className, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'quiet' }) {
  return <button type="button" {...rest} className={cx('oc-btn', 'oc-btn-' + variant, className)} />;
}
