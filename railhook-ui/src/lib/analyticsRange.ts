import { fromLocalDatetime } from './date';

export const PRESETS = ['24h', '7d', '30d'] as const;
export type Preset = (typeof PRESETS)[number];
export type AnalyticsRange = { period: Preset } | { from: string; to: string };

export function rangeQuery(range: AnalyticsRange): string {
  return 'period' in range
    ? `period=${range.period}`
    : `from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`;
}

export function customRange(fromLocal: string, toLocal: string): AnalyticsRange | null {
  if (!fromLocal || !toLocal) return null;
  const from = fromLocalDatetime(fromLocal);
  const to = fromLocalDatetime(toLocal);
  return from < to ? { from, to } : null;
}
