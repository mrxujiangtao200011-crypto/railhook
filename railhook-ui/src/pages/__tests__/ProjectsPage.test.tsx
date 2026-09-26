import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage } from '../../test/renderPage';
import type { ProjectResponse } from '../../types/api.types';

vi.mock('../../api/projects.api', () => ({
  projectsApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock('../../api/dashboard.api', () => ({
  dashboardApi: { getProjectStats: vi.fn().mockResolvedValue({}) },
}));

import ProjectsPage from '../ProjectsPage';
import { projectsApi } from '../../api/projects.api';

const PROJECT: ProjectResponse = {
  id: 'project-1',
  name: 'Production',
  description: 'The real one',
  createdAt: new Date('2026-08-01T00:00:00Z').toISOString(),
} as ProjectResponse;

function renderProjects() {
  return renderPage(<ProjectsPage />, { path: '/projects', initialEntry: '/projects' });
}

describe('ProjectsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.list).mockResolvedValue([PROJECT]);
  });

  it('tells a new account what a project is and how to make one', async () => {
    vi.mocked(projectsApi.list).mockResolvedValue([]);
    renderProjects();

    expect(await screen.findByText(en.projects.noProjects)).toBeInTheDocument();
    expect(screen.getByText(en.projects.noProjectsDesc)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.projects.createFirst })).toBeInTheDocument();
  });

  it('asks for a confirmation before deleting a project', async () => {
    renderProjects();
    await screen.findByText('Production');

    await userEvent.click(screen.getByRole('button', { name: en.projects.deleteNamed.replace('{{name}}', 'Production') }));

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(projectsApi.delete).not.toHaveBeenCalled();
  });

  it('says the list failed to load rather than offering to create a first project', async () => {
    vi.mocked(projectsApi.list).mockRejectedValue({ response: { status: 500, data: { message: 'Projects are unavailable' } } });
    renderProjects();

    expect(await screen.findByRole('alert')).toHaveTextContent('Projects are unavailable');
    expect(screen.queryByText(en.projects.noProjects)).not.toBeInTheDocument();
  });
});
