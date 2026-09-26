import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: { list: vi.fn(), get: vi.fn() },
}));
vi.mock('../../api/auth.api', () => ({
  authApi: { getCurrentUser: vi.fn().mockResolvedValue(null), logout: vi.fn(), resendVerification: vi.fn() },
}));

import AppLayout from '../../layout/AppLayout';
import { projectsApi } from '../../api/projects.api';

const project = (id: string, name: string): ProjectResponse => ({
  id, name,
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
});

function renderAt(path: string) {
  return renderPage(<AppLayout />, { path: '/admin/*', initialEntry: path });
}

/** Room for a slow CI runner (it failed at 1313ms there); a bad href still fails on the first poll. */
const SETTLE_MS = 8_000;

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
});

describe('the project the rail falls back to', () => {
  it.each([
    {
      name: 'the project you were last in, not the first one',
      projects: [project('first-project', 'test'), project(TEST_PROJECT_ID, 'load-test')],
      remembered: TEST_PROJECT_ID,
      path: '/admin/dashboard',
    },
    {
      name: 'the first project again when the remembered one no longer exists',
      projects: [project(TEST_PROJECT_ID, 'Production')],
      remembered: 'deleted-project',
      path: '/admin/dashboard',
    },
    {
      name: 'a real project when the URL names none',
      projects: [project(TEST_PROJECT_ID, 'Production')],
      remembered: undefined,
      path: '/admin/projects',
    },
  ])('is $name', async ({ projects, remembered, path }) => {
    vi.mocked(projectsApi.list).mockResolvedValue(projects);
    if (remembered) localStorage.setItem('railhook:last-project', remembered);
    renderAt(path);

    await waitFor(() =>
      expect(screen.getByRole('link', { name: /Deliveries/i }))
        .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/deliveries`), { timeout: SETTLE_MS });
  });

  it('remembers a project once you open it', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([
      project('first-project', 'test'),
      project(TEST_PROJECT_ID, 'load-test'),
    ]);
    renderAt(`/admin/projects/${TEST_PROJECT_ID}/endpoints`);

    await waitFor(() => expect(localStorage.getItem('railhook:last-project')).toBe(TEST_PROJECT_ID),
      { timeout: SETTLE_MS });
  });
});

describe('the rail without a project in the URL', () => {
  it('names the project it fell back to instead of asking you to pick one', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([project(TEST_PROJECT_ID, 'Production')]);
    renderAt('/admin/projects');

    expect(await screen.findByText('Production', undefined, { timeout: SETTLE_MS })).toBeInTheDocument();
    expect(screen.queryByText(/Select project/i)).toBeNull();
  });

  it('sends each entry to its own setup screen when the account has none', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([]);
    renderAt('/admin/dashboard');

    const deliveries = await screen.findByRole('link', { name: /Deliveries/i });
    expect(deliveries).toHaveAttribute('href', '/admin/start/deliveries');
  });

  it('leaves the project in the URL alone', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([
      project('other-project', 'Staging'),
      project(TEST_PROJECT_ID, 'Production'),
    ]);
    renderAt(`/admin/projects/${TEST_PROJECT_ID}/deliveries`);

    await waitFor(() =>
      expect(screen.getByRole('link', { name: /^Events/i }))
        .toHaveAttribute('href', `/admin/projects/${TEST_PROJECT_ID}/events`), { timeout: SETTLE_MS });
  });
});
