import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

import GoogleSignInButton from '../GoogleSignInButton';
import { authApi } from '../../api/auth.api';

describe('GoogleSignInButton', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  function renderButton(entry = '/login', returnTo?: string) {
    return render(
      <MemoryRouter initialEntries={[entry]}>
        <GoogleSignInButton intent="login" returnTo={returnTo} />
      </MemoryRouter>,
    );
  }

  it('links to the API start endpoint when the deployment has Google configured', async () => {
    vi.spyOn(authApi, 'providers').mockResolvedValue({ google: true });

    renderButton('/login', '/admin/projects');

    const link = await screen.findByRole('link', { name: /google/i });
    const href = new URL(link.getAttribute('href') ?? '', 'http://localhost');
    expect(href.pathname).toBe('/api/v1/auth/oauth/google/start');
    expect(href.searchParams.get('intent')).toBe('login');
    expect(href.searchParams.get('returnTo')).toBe('/admin/projects');
  });

  it.each([
    ['Google is not configured', () => Promise.resolve({ google: false })],
    ['the API cannot say', () => Promise.reject(new Error('Network Error'))],
  ])('shows nothing when %s', async (_, reply) => {
    const providers = vi.spyOn(authApi, 'providers').mockImplementation(reply);

    renderButton();

    await waitFor(() => expect(providers).toHaveBeenCalled());
    expect(screen.queryByRole('link', { name: /google/i })).not.toBeInTheDocument();
  });

  it('says why a Google sign-in came back without signing in', async () => {
    vi.spyOn(authApi, 'providers').mockResolvedValue({ google: true });

    renderButton('/login?error=google_denied');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent?.trim()).not.toBe('');
    expect(alert).not.toHaveTextContent('auth.google');
  });

  it('ignores an error parameter that is not about Google', async () => {
    vi.spyOn(authApi, 'providers').mockResolvedValue({ google: true });

    renderButton('/login?error=<script>');

    await screen.findByRole('link', { name: /google/i });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
