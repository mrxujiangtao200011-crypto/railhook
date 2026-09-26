import { afterEach, describe, expect, it } from 'vitest';

import { siteUrl } from '../siteUrl';

/** Unconfigured must resolve to this deployment, never a guessed domain. */
describe('siteUrl', () => {
  afterEach(() => {
    delete window.__RAILHOOK__;
  });

  it.each([
    ['nothing is configured', { siteUrl: '' }],
    ['there is no runtime config at all', undefined],
    ['the value is only whitespace, rather than emitting a bare path', { siteUrl: '   ' }],
  ])('uses the origin the page is served from when %s', (_, config) => {
    window.__RAILHOOK__ = config;

    expect(siteUrl()).toBe(window.location.origin);
  });

  it("prefers the container's configured origin, because prerender has no meaningful window", () => {
    window.__RAILHOOK__ = { siteUrl: 'https://hooks.example.com' };

    expect(siteUrl()).toBe('https://hooks.example.com');
  });

  it('strips trailing slashes so a path can be appended directly', () => {
    window.__RAILHOOK__ = { siteUrl: 'https://hooks.example.com//' };

    expect(`${siteUrl()}/pricing`).toBe('https://hooks.example.com/pricing');
  });
});
