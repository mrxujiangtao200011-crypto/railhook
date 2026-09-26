import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import NotFoundPage from '../NotFoundPage';
import { renderPage } from '../../test/renderPage';

describe('NotFoundPage', () => {
  function renderAt(entry: string, auth?: { isAuthenticated: boolean }) {
    return renderPage(<NotFoundPage />, { path: '*', initialEntry: entry, auth });
  }

  it.each([
    ['a signed-in user back to the dashboard', '/admin/nope', true, '/admin/dashboard'],
    ['a signed-out visitor back to the front page', '/nope', false, '/'],
  ])('sends %s', (_, entry, isAuthenticated, href) => {
    renderAt(entry, { isAuthenticated });

    expect(screen.getByRole('link')).toHaveAttribute('href', href);
  });

  it('names the path that missed', () => {
    renderAt('/admin/typo', { isAuthenticated: true });

    expect(screen.getByText(/\/admin\/typo/)).toBeInTheDocument();
  });
});
