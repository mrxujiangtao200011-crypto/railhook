import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type {
  AlertChannelResponse, AlertConditionResponse, AlertEventResponse, AlertRuleResponse,
} from '../../types/api.types';

vi.mock('../../api/alerts.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/alerts.api')>()),
  alertsApi: {
    channels: vi.fn(),
    conditions: vi.fn(),
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

vi.mock('../../api/endpoints.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/endpoints.api')>()),
  endpointsApi: { list: vi.fn().mockResolvedValue([]) },
}));

import AlertsPage from '../AlertsPage';
import { alertsApi } from '../../api/alerts.api';

const now = new Date().toISOString();
const rule = (id: string, name: string, extra: Partial<AlertRuleResponse> = {}): AlertRuleResponse => ({
  id, projectId: TEST_PROJECT_ID, name, description: null, alertType: 'FAILURE_RATE', severity: 'CRITICAL', channel: 'SLACK',
  channelConfig: {}, configuredSecrets: [], thresholdValue: 5, windowMinutes: 15, endpointId: null, enabled: true,
  muted: false, snoozedUntil: null, createdAt: now, updatedAt: now, ...extra,
});
const firing: AlertEventResponse = {
  id: 'ev-1', alertRuleId: 'r-2', projectId: TEST_PROJECT_ID, severity: 'CRITICAL', title: 'Failure rate 22.4%', message: null,
  currentValue: 22.4, thresholdValue: 5, resolved: false, resolvedAt: null, createdAt: now,
};
const page = <T,>(content: T[]) => ({ content, totalElements: content.length, totalPages: 1, size: 20, number: 0, first: true, last: true });

const conditions: AlertConditionResponse[] = [{
  id: 'FAILURE_RATE', displayName: 'Failure rate',
  configSchema: {
    type: 'object', required: ['thresholdValue'],
    properties: {
      thresholdValue: { type: 'number', title: 'Failure rate (%)' },
      windowMinutes: { type: 'integer', title: 'Window (minutes)', default: 5 },
    },
  },
}];
// A channel the UI has no code for: everything it shows comes from this schema.
const channels: AlertChannelResponse[] = [
  { id: 'IN_APP', displayName: 'In-app', pages: false, configSchema: { type: 'object', properties: {}, required: [] } },
  {
    id: 'TEAMS', displayName: 'Teams', pages: false,
    configSchema: {
      type: 'object', required: ['url'],
      properties: {
        url: { type: 'string', title: 'Teams workflow URL', format: 'uri', writeOnly: true },
        mention: { type: 'string', title: 'Mention', description: 'Who to tag in the post' },
      },
    },
  },
];

describe('AlertsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(alertsApi.channels).mockResolvedValue(channels);
    vi.mocked(alertsApi.conditions).mockResolvedValue(conditions);
    vi.mocked(alertsApi.listRules).mockResolvedValue([rule('r-1', 'Latency watch', { muted: true }), rule('r-2', 'Failure rate above 5%')]);
    vi.mocked(alertsApi.listEvents).mockResolvedValue(page([firing]) as never);
    vi.mocked(alertsApi.unresolvedCount).mockResolvedValue({ count: 1 });
    vi.mocked(alertsApi.updateRule).mockResolvedValue(rule('r-3', 'Teams rule'));
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

  it("builds the channel's fields from its schema, and leaves a stored secret out of the form and the update", async () => {
    vi.mocked(alertsApi.listRules).mockResolvedValue([rule('r-3', 'Teams rule', {
      channel: 'TEAMS', channelConfig: { mention: '@oncall' }, configuredSecrets: ['url'],
    })]);
    renderPage(<AlertsPage />, { path: '/projects/:projectId/alerts', initialEntry: `/projects/${TEST_PROJECT_ID}/alerts` });

    await userEvent.click(await screen.findByRole('button', { name: 'Edit Teams rule' }));

    const secret = await screen.findByLabelText('Teams workflow URL');
    expect(secret).toHaveAttribute('type', 'password');
    expect(secret).toHaveValue('');
    expect(secret).toHaveAttribute('placeholder', 'Stored. Leave blank to keep it.');
    expect(screen.getByLabelText('Mention')).toHaveValue('@oncall');
    expect(screen.getByText('Who to tag in the post')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(alertsApi.updateRule).toHaveBeenCalledWith(TEST_PROJECT_ID, 'r-3', expect.objectContaining({
      channel: 'TEAMS', channelConfig: { mention: '@oncall' },
    }));
  });
});
