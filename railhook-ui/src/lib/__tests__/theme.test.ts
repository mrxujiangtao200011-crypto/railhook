import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getTheme, isDarkApplied, setTheme, toggleTheme } from '../theme';

/** With nothing stored, 'system' on a dark machine made the first toggle choose dark again. */
describe('toggleTheme', () => {
  function systemPrefersDark(dark: boolean) {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: dark && query.includes('dark'),
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }));
  }

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('light', 'dark');
  });

  it.each([
    [true, 'light'],
    [false, 'dark'],
  ] as const)('turns a system default (dark: %s) to %s on the first click', (dark, next) => {
    systemPrefersDark(dark);
    setTheme('system');
    expect(getTheme()).toBe('system');
    expect(isDarkApplied()).toBe(dark);

    expect(toggleTheme()).toBe(next);
    expect(isDarkApplied()).toBe(!dark);
    expect(getTheme()).toBe(next);
  });

  it('keeps alternating after the first click', () => {
    systemPrefersDark(true);
    setTheme('system');

    expect(toggleTheme()).toBe('light');
    expect(toggleTheme()).toBe('dark');
    expect(toggleTheme()).toBe('light');
  });
});
