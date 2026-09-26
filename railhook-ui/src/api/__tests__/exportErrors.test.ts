import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../http', () => ({ http: { getBlob: vi.fn() } }));

import { http } from '../http';
import { auditLogApi } from '../auditLog.api';
import { organizationsApi } from '../organizations.api';
import { resolveErrorMessage } from '../../lib/toast';

function blobBodiedError(status: number, body: unknown) {
  return {
    response: { status, data: new Blob([JSON.stringify(body)], { type: 'application/json' }) },
  };
}

describe('file exports report the server message when they fail', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it.each([
    { name: 'audit log CSV export', status: 400, message: 'Date range exceeds 90 days', run: () => auditLogApi.exportCsv() },
    { name: 'organization data export', status: 403, message: 'Only the owner can export', run: () => organizationsApi.exportData('org-1') },
  ])('$name', async ({ status, message, run }) => {
    vi.mocked(http.getBlob).mockRejectedValue(blobBodiedError(status, { message }));

    const err = await run().catch((e: unknown) => e);

    expect(resolveErrorMessage(err, 'toast.errors.server')).toBe(message);
  });

  it('leaves a body that is not JSON as it was', async () => {
    const original = { response: { status: 502, data: new Blob(['<html>bad gateway</html>']) } };
    vi.mocked(http.getBlob).mockRejectedValue(original);

    const err = await auditLogApi.exportCsv().catch((e: unknown) => e);

    expect(err).toBe(original);
    expect(resolveErrorMessage(err, 'toast.errors.server')).not.toContain('html');
  });
});
