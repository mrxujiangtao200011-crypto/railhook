import { describe, it, expect, vi, afterEach } from 'vitest';

vi.mock('../../i18n', () => ({
  default: {
    language: 'en',
    t: (key: string, opts?: Record<string, unknown>) => {
      const translations: Record<string, string> = {
        'relativeTime.justNow': 'just now',
        'relativeTime.minutesAgo': `${opts?.count}m ago`,
        'relativeTime.hoursAgo': `${opts?.count}h ago`,
        'relativeTime.daysAgo': `${opts?.count}d ago`,
      };
      return translations[key] || key;
    },
  },
}));

import { formatRelativeTime } from '../date';

describe('formatRelativeTime', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it.each([
    ['10 seconds', 10_000, /^just now$/],
    ['5 minutes', 5 * 60_000, /^5m ago$/],
    ['3 hours', 3 * 3600_000, /^3h ago$/],
    ['2 days', 2 * 86400_000, /^2d ago$/],
    ['10 days, past which it shows the date', 10 * 86400_000, /2025/],
  ])('%s ago', (_, ago, expected) => {
    vi.useFakeTimers();
    const now = new Date('2025-03-01T12:00:00Z');
    vi.setSystemTime(now);
    expect(formatRelativeTime(new Date(now.getTime() - ago).toISOString())).toMatch(expected);
  });
});
