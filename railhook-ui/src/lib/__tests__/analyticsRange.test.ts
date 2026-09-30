import { describe, expect, it } from 'vitest';
import { customRange, rangeQuery } from '../analyticsRange';

describe('analytics range', () => {
  it('sends a preset as period and a custom range as encoded from and to', () => {
    expect(rangeQuery({ period: '7d' })).toBe('period=7d');
    expect(rangeQuery({ from: '2026-09-29T10:00:00.000Z', to: '2026-09-30T10:00:00.000Z' }))
      .toBe('from=2026-09-29T10%3A00%3A00.000Z&to=2026-09-30T10%3A00%3A00.000Z');
  });

  it('reads the picker as local time and refuses a range that does not end after it starts', () => {
    expect(customRange('2026-09-29T10:00', '2026-09-29T12:30')).toEqual({
      from: new Date(2026, 8, 29, 10, 0).toISOString(),
      to: new Date(2026, 8, 29, 12, 30).toISOString(),
    });
    expect(customRange('2026-09-29T10:00', '2026-09-29T10:00')).toBeNull();
    expect(customRange('2026-09-29T10:00', '')).toBeNull();
  });
});
