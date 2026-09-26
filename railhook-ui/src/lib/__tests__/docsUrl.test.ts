import { describe, expect, it } from 'vitest';
import { docsUrl } from '../docsUrl';

describe('docsUrl', () => {
  it.each([
    ['the docs home, with a trailing slash', 'en', undefined, '/docs/'],
    ['a page under the English root', 'en', 'outgoing/retries', '/docs/outgoing/retries/'],
    ['the Ukrainian copy for a Ukrainian reader', 'uk', 'outgoing/retries', '/docs/uk/outgoing/retries/'],
    ['the Ukrainian home', 'uk', undefined, '/docs/uk/'],
    ['a regional tag as its language', 'uk-UA', 'tools/cli', '/docs/uk/tools/cli/'],
    ['an English regional tag as English', 'en-GB', 'tools/cli', '/docs/tools/cli/'],
    ['English for a language the docs are not written in', 'de', 'tools/cli', '/docs/tools/cli/'],
    ['English when the language is unknown', undefined, undefined, '/docs/'],
    ['a slug with slashes around it', 'en', '/outgoing/replay/', '/docs/outgoing/replay/'],
  ])('addresses %s', (_, language, slug, url) => {
    expect(docsUrl(language, slug)).toBe(url);
  });
});
