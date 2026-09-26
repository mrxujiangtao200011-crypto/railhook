import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { nginxLocations } from '../test/nginxLocations';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

const SNIPPET = '/etc/nginx/snippets/security-headers.conf';
const COMMON = '/etc/nginx/snippets/security-headers-common.conf';

const FRAMEABLE = '= /portal';

/** nginx inherits add_header only into a location that sets none of its own. */

describe('nginx security headers', () => {
  const conf = read('railhook-ui/nginx.conf');

  it('keeps the headers in snippets the image ships', () => {
    const snippet = read('railhook-ui/nginx-security-headers.conf');
    const common = read('railhook-ui/nginx-security-headers-common.conf');
    expect(snippet).toMatch(/^\s*add_header X-Frame-Options "SAMEORIGIN" always;/m);
    expect(snippet).toMatch(new RegExp(`^\\s*include ${COMMON};`, 'm'));
    for (const header of ['X-Content-Type-Options', 'Referrer-Policy', 'Permissions-Policy']) {
      expect(common, header).toMatch(new RegExp(`^\\s*add_header ${header} `, 'm'));
    }
    const dockerfile = read('railhook-ui/Dockerfile');
    expect(dockerfile).toMatch(new RegExp(`COPY railhook-ui/nginx-security-headers.conf ${SNIPPET}`));
    expect(dockerfile).toMatch(new RegExp(`COPY railhook-ui/nginx-security-headers-common.conf ${COMMON}`));
  });

  it('finds the locations it is meant to be checking', () => {
    expect(nginxLocations(conf).filter((b) => /add_header/.test(b.body)).length).toBeGreaterThanOrEqual(3);
  });

  it('every location that adds a header of its own also includes the security headers', () => {
    const missing = nginxLocations(conf)
      .filter((b) => /^\s*add_header\s/m.test(b.body))
      .filter((b) => !b.body.includes(`include ${b.head === FRAMEABLE ? COMMON : SNIPPET};`))
      .map((b) => b.head);
    expect(missing).toEqual([]);
  });
});
