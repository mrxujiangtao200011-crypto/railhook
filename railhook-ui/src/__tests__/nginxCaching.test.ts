import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { nginxLocations } from '../test/nginxLocations';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const conf = readFileSync(join(repoRoot, 'railhook-ui/nginx.conf'), 'utf8');

/** Only hashed names may be immutable: browsers kept showing old public/ files after deploys. */
const locations = () => nginxLocations(conf);

const HASHED = ['^~ /assets/', '^~ /docs/_astro/'];

describe('nginx caching', () => {
  it('marks only content-hashed paths immutable', () => {
    const immutable = locations().filter((l) => /immutable/.test(l.body)).map((l) => l.head);
    expect(immutable.sort()).toEqual([...HASHED].sort());
  });

  it('makes the extension-matched static files revalidate instead', () => {
    const byExtension = locations().find((l) => l.head.startsWith('~*') && /png/.test(l.head));
    expect(byExtension, 'the static-file location by extension').toBeDefined();
    expect(byExtension!.body).toMatch(/add_header\s+Cache-Control\s+"no-cache"/);
    expect(byExtension!.body).not.toMatch(/expires\s+1y/);
  });
});
