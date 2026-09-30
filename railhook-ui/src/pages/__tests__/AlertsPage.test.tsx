import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { AlertEventResponse, AlertRuleResponse } from '../../api/alerts.api';

vi.mock('../../api/alerts.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/alerts.api')>()),
  alertsApi: {
    listRules: vi.fn(),
    listEvents: vi.fn(),
    unresolvedCount: vi.fn(),
    createRule: vi.fn(),
    updateRule: vi.fn(),
    deleteRule: vi.fn(),
    resolveEvent: vi.fn(),
    resolveAll: vi.fn(),
  },
}));

import AlertsPage from '../AlertsPage';
import { alertsApi } from '../../api/alerts.api';

const now = new Date().toISOString();
const rule = (id: string, name: string, extra: Partial<AlertRuleResponse> = {}): AlertRuleResponse => ({
  id, projectId: TEST_PROJECT_ID, name, description: null, alertType: 'FAILURE_RATE', severity: 'CRITICAL', channel: 'SLACK',
  thresholdValue: 5, windowMinutes: 15, endpointId: null, enabled: true, muted: false, snoozedUntil: null, webhookUrl: null,
  emailRecipients: null, integrationKeyConfigured: false, opsgenieRegion: null, createdAt: now, updatedAt: now, ...extra,
});
const firing: AlertEventResponse = {
  id: 'ev-1', alertRuleId: 'r-2', projectId: TEST_PROJECT_ID, severity: 'CRITICAL', title: 'Failure rate 22.4%', message: null,
  currentValue: 22.4, thresholdValue: 5, resolved: false, resolvedAt: null, createdAt: now,
};
const page = <T,>(content: T[]) => ({ content, totalElements: content.length, totalPages: 1, size: 20, number: 0, first: true, last: true });

describe('AlertsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(alertsApi.listRules).mockResolvedValue([rule('r-1', 'Latency watch', { muted: true }), rule('r-2', 'Failure rate above 5%')]);
    vi.mocked(alertsApi.listEvents).mockResolvedValue(page([firing]) as never);
    vi.mocked(alertsApi.unresolvedCount).mockResolvedValue({ count: 1 });
  });

  it('puts the firing rule first, says what it is at, and says the condition in words', async () => {
    renderPage(<AlertsPage />, { path: '/projects/:projectId/alerts', initialEntry: `/projects/${TEST_PROJECT_ID}/alerts` });

    const rows = await screen.findAllByRole('row');
    const firingRow = rows.find((r) => r.getAttribute('data-firing'));
    expect(firingRow).toBeDefined();
    expect(within(firingRow!).getByText('Failure rate above 5%', { selector: 'p' })).toBeInTheDocument();
    expect(within(firingRow!).getByText('22.4 now, limit 5')).toBeInTheDocument();
    expect(within(firingRow!).getByText('Failure rate above 5% over 15 min')).toBeInTheDocument();

    const bodyRows = rows.filter((r) => r.closest('tbody'));
    expect(bodyRows[0]).toBe(firingRow);
    expect(screen.getByText('Muted')).toBeInTheDocument();
  });
});
