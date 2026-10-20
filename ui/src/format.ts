/** `1h 12m`, `45m`, `<1m`, `0m` — the Orb design system's time format. */
export function formatMinutes(m: number): string {
  if (m <= 0) return '0m';
  if (m < 1) return '<1m';
  m = Math.round(m);
  const h = Math.floor(m / 60), mn = m % 60;
  return h ? `${h}h${mn ? ` ${mn}m` : ''}` : `${mn}m`;
}

export function clockTime(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
