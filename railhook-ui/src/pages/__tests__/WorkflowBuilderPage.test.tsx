import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { WorkflowResponse } from '../../api/workflows.api';

vi.mock('../../api/workflows.api', () => ({
  workflowsApi: {
    get: vi.fn(),
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    toggle: vi.fn(),
    trigger: vi.fn(),
    listExecutions: vi.fn(),
    getExecution: vi.fn(),
    previewSchedule: vi.fn(),
  },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn().mockResolvedValue([]), create: vi.fn() },
}));
vi.mock('../../api/transformations.api', () => ({
  transformationsApi: { list: vi.fn().mockResolvedValue([]), create: vi.fn() },
}));
vi.mock('../../api/subscriptions.api', () => ({
  subscriptionsApi: { list: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../../api/apiKeys.api', () => ({
  apiKeysApi: { list: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../../api/schemas.api', () => ({
  schemasApi: { listEventTypes: vi.fn().mockResolvedValue([]) },
}));

import WorkflowBuilderPage from '../WorkflowBuilderPage';
import { workflowsApi } from '../../api/workflows.api';

const WORKFLOW: WorkflowResponse = {
  id: 'workflow-1',
  projectId: TEST_PROJECT_ID,
  name: 'Route payments',
  description: null,
  enabled: false,
  definition: {
    nodes: [{ id: 'n1', type: 'transform', position: { x: 0, y: 0 }, data: { label: 'Reshape' } }],
    edges: [],
  } as unknown as WorkflowResponse['definition'],
  triggerType: 'WEBHOOK_EVENT',
  triggerConfig: {},
  version: 1,
  createdAt: new Date('2026-08-01T00:00:00Z').toISOString(),
  updatedAt: new Date('2026-08-01T00:00:00Z').toISOString(),
  totalExecutions: 0,
  successfulExecutions: 0,
  failedExecutions: 0,
} as WorkflowResponse;

const emptyExecutions = {
  content: [], totalElements: 0, totalPages: 0, size: 10, number: 0, first: true, last: true,
};

function renderBuilder() {
  return renderPage(<WorkflowBuilderPage />, {
    path: '/projects/:projectId/workflows/:workflowId',
    initialEntry: `/projects/${TEST_PROJECT_ID}/workflows/workflow-1`,
  });
}

describe('WorkflowBuilderPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(workflowsApi.get).mockResolvedValue(WORKFLOW);
    vi.mocked(workflowsApi.listExecutions).mockResolvedValue(emptyExecutions as never);
  });

  it('opens a workflow whose nodes carry no position', async () => {
    vi.mocked(workflowsApi.get).mockResolvedValue({
      ...WORKFLOW,
      definition: {
        nodes: [
          { id: 'start', type: 'webhookTrigger', data: {} },
          { id: 'reshape', type: 'transform', data: { template: '{"a":1}' } },
        ],
        edges: [{ source: 'start', target: 'reshape' }],
      } as unknown as WorkflowResponse['definition'],
    });

    renderBuilder();

    expect(await screen.findByText('Route payments')).toBeInTheDocument();
    expect(screen.queryByText(/reading 'x'/)).not.toBeInTheDocument();
  });

  it('adds a node when a palette entry is tapped', async () => {
    renderBuilder();
    await screen.findByText('Route payments');
    await waitFor(() => expect(document.body.textContent).toMatch(/1 nodes/));

    await userEvent.click(screen.getByRole('button', { name: /delay/i }));

    await waitFor(() => expect(document.body.textContent).toMatch(/2 nodes/));
    expect(screen.getByText(/unsaved/i)).toBeInTheDocument();
  });

  it('opens a workflow with nothing to save', async () => {
    renderBuilder();
    await screen.findByText('Route payments');

    await waitFor(() => expect(screen.getByRole('button', { name: /^save/i })).toBeDisabled());
    expect(screen.queryByText(/unsaved/i)).not.toBeInTheDocument();
  });

  it('does not fire a test run from opening the test-run panel', async () => {
    renderBuilder();
    await screen.findByText('Route payments');

    await userEvent.click(screen.getByRole('button', { name: 'Test Run' }));

    expect(await screen.findByText('Provide a JSON payload to manually trigger this workflow')).toBeInTheDocument();
    expect(workflowsApi.trigger).not.toHaveBeenCalled();
  });

  it('keeps unsaved canvas edits when the workflow is enabled or disabled', async () => {
    renderBuilder();
    await screen.findByText('Route payments');

    fireEvent.click(await screen.findByText('Reshape'));
    fireEvent.keyDown(window, { key: 'Delete' });
    await waitFor(() => expect(screen.queryByText('Reshape')).not.toBeInTheDocument());
    expect(screen.getByText(/unsaved/i)).toBeInTheDocument();

    vi.mocked(workflowsApi.toggle).mockResolvedValue({ ...WORKFLOW, enabled: true } as never);
    vi.mocked(workflowsApi.get).mockResolvedValue({ ...WORKFLOW, enabled: true });
    fireEvent.click(screen.getByRole('button', { name: /^disabled$/i }));

    await screen.findByRole('button', { name: /^enabled$/i });
    expect(screen.getByText(/unsaved/i)).toBeInTheDocument();
    expect(screen.queryByText('Reshape')).not.toBeInTheDocument();
  });

  it('saves a schedule picked on the trigger node and previews its next runs', async () => {
    vi.mocked(workflowsApi.get).mockResolvedValue({
      ...WORKFLOW,
      definition: {
        nodes: [{ id: 'start', type: 'webhookTrigger', position: { x: 0, y: 0 }, data: { label: 'Start' } }],
        edges: [],
      } as unknown as WorkflowResponse['definition'],
    });
    vi.mocked(workflowsApi.previewSchedule).mockResolvedValue([
      '2026-10-01T06:00:00Z', '2026-10-02T06:00:00Z', '2026-10-05T06:00:00Z',
    ]);
    vi.mocked(workflowsApi.update).mockResolvedValue(WORKFLOW);
    renderBuilder();

    fireEvent.click(await screen.findByText('Start'));
    await userEvent.selectOptions(screen.getByLabelText('Trigger'), 'SCHEDULE');
    await userEvent.type(screen.getByLabelText(/cron expression/i), '0 9 * * 1-5');
    await userEvent.type(screen.getByLabelText('Time zone'), 'Europe/Kyiv');

    await waitFor(() => expect(workflowsApi.previewSchedule)
      .toHaveBeenCalledWith(TEST_PROJECT_ID, '0 9 * * 1-5', 'Europe/Kyiv'));
    expect(await screen.findAllByRole('listitem')).toHaveLength(3);

    await userEvent.click(screen.getByRole('button', { name: /^save/i }));
    await waitFor(() => expect(workflowsApi.update).toHaveBeenCalledWith(TEST_PROJECT_ID, 'workflow-1',
      expect.objectContaining({
        triggerType: 'SCHEDULE',
        triggerConfig: { cron: '0 9 * * 1-5', timezone: 'Europe/Kyiv' },
      })));
  });
});
