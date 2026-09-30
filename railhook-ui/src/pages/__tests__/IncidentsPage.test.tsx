import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { IncidentResponse } from '../../api/incidents.api';

vi.mock('../../api/incidents.api', () => ({
  incidentsApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    addTimeline: vi.fn(),
    countOpen: vi.fn(),
  },
}));

import IncidentsPage from '../IncidentsPage';
import { incidentsApi } from '../../api/incidents.api';

const now = new Date('2026-08-01T00:00:00Z').toISOString();

const incident = (over: Partial<IncidentResponse>): IncidentResponse => ({
  id: 'incident-1',
  projectId: TEST_PROJECT_ID,
  title: 'Checkout webhooks failing',
  status: 'OPEN',
  severity: 'WARNING',
  rcaNotes: null,
  alertRuleId: null,
  alertRuleName: null,
  autoResolved: false,
  resolvedAt: null,
  createdAt: now,
  updatedAt: now,
  timeline: null,
  ...over,
});

const page = (content: IncidentResponse[], totalElements = content.length) => ({
  content,
  totalElements,
  totalPages: Math.max(1, Math.ceil(totalElements / 20)),
  size: 20,
  number: 0,
  first: true,
  last: totalElements <= 20,
});

function renderIncidents() {
  return renderPage(<IncidentsPage />, {
    path: '/projects/:projectId/incidents',
    initialEntry: `/projects/${TEST_PROJECT_ID}/incidents`,
  });
}

function countOf(label: RegExp): string | undefined {
  const term = screen.queryAllByText(label).find((el) => el.tagName === 'DT');
  return term?.nextElementSibling?.textContent ?? undefined;
}

describe('IncidentsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(incidentsApi.list).mockResolvedValue(page([incident({})]));
    vi.mocked(incidentsApi.countOpen).mockResolvedValue({ count: 1, investigating: 0, critical: 0 });
  });

  it('counts incidents the list has not loaded', async () => {
    // Every count is a number the rows on screen cannot produce.
    vi.mocked(incidentsApi.list).mockResolvedValue(
      page(Array.from({ length: 20 }, (_, i) => incident({ id: `incident-${i}`, title: `Routine ${i}` })), 24),
    );
    vi.mocked(incidentsApi.countOpen).mockResolvedValue({ count: 24, investigating: 2, critical: 1 });

    renderIncidents();

    await screen.findByText('Routine 0');
    await waitFor(() => expect(countOf(/investigat/i)).toBe('2'));
    expect(countOf(/critical/i)).toBe('1');
    expect(countOf(/^open$/i)).toBe('24');
  });

  it('says all clear only when nothing is unresolved', async () => {
    vi.mocked(incidentsApi.list).mockResolvedValue(page([]));
    vi.mocked(incidentsApi.countOpen).mockResolvedValue({ count: 0, investigating: 0, critical: 0 });

    renderIncidents();

    await waitFor(() => expect(incidentsApi.countOpen).toHaveBeenCalled());
    expect(await screen.findByText('All clear')).toBeInTheDocument();
    expect(countOf(/^open$/i)).toBeUndefined();
    expect(document.body.textContent).not.toMatch(/incidents\.tiles/);
  });

  it('opens the incident with its timeline when you pick it from the list', async () => {
    vi.mocked(incidentsApi.get).mockResolvedValue(incident({
      timeline: [{ id: 't-1', entryType: 'NOTE', title: 'Gateway rolled back', detail: null, deliveryId: null, endpointId: null, createdAt: now }],
    }));

    renderIncidents();
    fireEvent.click(await screen.findByText('Checkout webhooks failing'));

    expect(await screen.findByText('Gateway rolled back')).toBeInTheDocument();
    expect(incidentsApi.get).toHaveBeenCalledWith(TEST_PROJECT_ID, 'incident-1');
  });

  it('names the alert rule that opened an incident and says it resolved on its own', async () => {
    vi.mocked(incidentsApi.list).mockResolvedValue(page([
      incident({ alertRuleId: 'rule-1', alertRuleName: 'Payments failing', status: 'RESOLVED', autoResolved: true }),
    ]));

    renderIncidents();

    expect(await screen.findByText('Opened by alert rule Payments failing')).toBeInTheDocument();
    expect(screen.getByText('Resolved automatically')).toBeInTheDocument();
  });

  it('says the list failed to load rather than showing no incidents', async () => {
    vi.mocked(incidentsApi.list).mockRejectedValue({ response: { status: 500, data: { message: 'Incident store unavailable' } } });
    renderIncidents();

    expect(await screen.findByRole('alert')).toHaveTextContent('Incident store unavailable');
    expect(screen.queryByText('Checkout webhooks failing')).not.toBeInTheDocument();
  });
});
