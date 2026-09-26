import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ReplaySessionResponse } from '../../api/replay.api';

vi.mock('../../api/replay.api', () => ({
  replayApi: {
    estimate: vi.fn(),
    create: vi.fn(),
    get: vi.fn(),
    list: vi.fn(),
    cancel: vi.fn(),
  },
}));

vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn().mockResolvedValue([]) },
}));

// Without this mock the load rejects and every assertion passes for the wrong reason.
vi.mock('../../api/projects.api', () => ({
  projectsApi: {
    get: vi.fn().mockResolvedValue({ id: 'project-1', name: 'Test Project' }),
  },
}));

import ReplayPage from '../ReplayPage';
import { replayApi } from '../../api/replay.api';

const page = (content: ReplaySessionResponse[]) => ({
  content,
  totalElements: content.length,
  totalPages: 1,
  size: 50,
  number: 0,
  first: true,
  last: true,
});

const RUNNING: ReplaySessionResponse = {
  id: 'session-1',
  projectId: TEST_PROJECT_ID,
  createdBy: 'user-1',
  status: 'RUNNING',
  fromDate: new Date('2026-08-01T00:00:00Z').toISOString(),
  toDate: new Date('2026-08-02T00:00:00Z').toISOString(),
  totalEvents: 1200,
  processedEvents: 300,
  deliveriesCreated: 300,
  errors: 0,
  progressPercent: 25,
} as ReplaySessionResponse;

const COMPLETED: ReplaySessionResponse = {
  ...RUNNING,
  id: 'session-2',
  status: 'COMPLETED',
  processedEvents: 1200,
  deliveriesCreated: 1200,
  progressPercent: 100,
};

function renderReplay() {
  return renderPage(<ReplayPage />, {
    path: '/projects/:projectId/replay',
    initialEntry: `/projects/${TEST_PROJECT_ID}/replay`,
  });
}

describe('ReplayPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(replayApi.list).mockResolvedValue(page([]));
  });

  it('does not replay anything by being opened', async () => {
    renderReplay();

    await waitFor(() => expect(replayApi.list).toHaveBeenCalled());
    expect(replayApi.create).not.toHaveBeenCalled();
    expect(replayApi.estimate).not.toHaveBeenCalled();
  });

  it.each([
    ['a running session with its progress', RUNNING, 'Running', /300\/1\D?200/],
    ['a completed session', COMPLETED, 'Completed', /1\D?200\/1\D?200/],
  ])('shows %s', async (_, session, status, progress) => {
    vi.mocked(replayApi.list).mockResolvedValue(page([session]));
    renderReplay();

    const row = (await screen.findByText(status)).closest('tr')!;
    expect(row).toHaveTextContent(progress);
  });

  it('says the history failed to load rather than showing no sessions', async () => {
    vi.mocked(replayApi.list).mockRejectedValue({ response: { status: 500, data: { message: 'Replay history unavailable' } } });
    renderReplay();

    expect(await screen.findByRole('alert')).toHaveTextContent('Replay history unavailable');
    expect(screen.queryByText('No replay sessions')).not.toBeInTheDocument();
  });

  it('renders an empty history without breaking', async () => {
    renderReplay();

    expect(await screen.findByText('No replay sessions')).toBeInTheDocument();
  });
});
