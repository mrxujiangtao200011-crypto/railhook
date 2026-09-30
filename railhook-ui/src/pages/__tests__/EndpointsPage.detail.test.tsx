import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { EndpointResponse, ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({ projectsApi: { get: vi.fn(), list: vi.fn() } }));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn(), listPaged: vi.fn(), create: vi.fn(), delete: vi.fn(), update: vi.fn(), rotateSecret: vi.fn(), test: vi.fn(), verify: vi.fn(), skipVerification: vi.fn() },
}));
vi.mock('../../api/dashboard.api', () => ({ dashboardApi: { getAnalytics: vi.fn() } }));
vi.mock('../../api/subscriptions.api', () => ({ subscriptionsApi: { list: vi.fn() } }));
vi.mock('../../api/deliveries.api', () => ({ deliveriesApi: { listByProject: vi.fn() } }));

import EndpointsPage from '../EndpointsPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';
import { dashboardApi } from '../../api/dashboard.api';
import { subscriptionsApi } from '../../api/subscriptions.api';
import { deliveriesApi } from '../../api/deliveries.api';

const now = new Date().toISOString();
const PROJECT = { id: TEST_PROJECT_ID, name: 'Shop', schemaValidationEnabled: false, schemaValidationPolicy: 'WARN', idempotencyPolicy: 'NONE', createdAt: now, updatedAt: now } as ProjectResponse;
const FAILING: EndpointResponse = {
  id: 'ep-1', projectId: TEST_PROJECT_ID, url: 'https://erp.example.com/orders', enabled: true,
  consecutiveFailures: 14, failingSince: now, createdAt: now, updatedAt: now,
};
const page = <T,>(content: T[]) => ({ content, totalElements: content.length, totalPages: 1, size: 20, number: 0, first: true, last: true });

describe('EndpointsPage — one endpoint up close', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(endpointsApi.listPaged).mockResolvedValue(page([FAILING]) as never);
    vi.mocked(dashboardApi.getAnalytics).mockResolvedValue({
      endpointPerformance: [{
        endpointId: 'ep-1', url: FAILING.url, enabled: true, totalDeliveries: 200, successfulDeliveries: 78, failedDeliveries: 122,
        successRate: 39, avgLatencyMs: 900, p95LatencyMs: 30000, lastDeliveryAt: now, status: 'FAILING',
      }],
    } as never);
    vi.mocked(subscriptionsApi.list).mockResolvedValue([
      { id: 's-1', projectId: TEST_PROJECT_ID, endpointId: 'ep-1', eventType: 'order.paid', enabled: true } as never,
      { id: 's-2', projectId: TEST_PROJECT_ID, endpointId: 'other', eventType: 'invoice.paid', enabled: true } as never,
    ]);
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue(page([]) as never);
  });

  it('says how the endpoint is failing, right in the list', async () => {
    renderPage(<EndpointsPage />, { path: '/projects/:projectId/endpoints', initialEntry: `/projects/${TEST_PROJECT_ID}/endpoints` });
    expect(await screen.findByText(/14 failed in a row/)).toBeInTheDocument();
    expect(await screen.findByText('39.0%')).toBeInTheDocument();
  });

  it('opens the endpoint with its health and only its own event types', async () => {
    const user = userEvent.setup();
    renderPage(<EndpointsPage />, { path: '/projects/:projectId/endpoints', initialEntry: `/projects/${TEST_PROJECT_ID}/endpoints` });

    await user.click(await screen.findByText('https://erp.example.com/orders'));

    const sheet = await screen.findByRole('dialog');
    expect(within(sheet).getByText('78 of 200')).toBeInTheDocument();
    expect(await within(sheet).findByText('order.paid')).toBeInTheDocument();
    expect(within(sheet).queryByText('invoice.paid')).not.toBeInTheDocument();
  });
});
