import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Webhook } from 'lucide-react';
import EmptyState, { ErrorState } from '../EmptyState';

import '../../i18n';

function withRouter(ui: React.ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

describe('EmptyState', () => {

  it('keeps its centred layout when a caller only changes the spacing', () => {
    // "py-10" used to replace the whole layout inside a card.
    withRouter(<EmptyState icon={Webhook} title="No projects." className="py-10" />);
    const container = screen.getByText('No projects.').parentElement!;
    expect(container).toHaveClass('flex', 'flex-col', 'items-center', 'justify-center', 'py-10');
    expect(container).not.toHaveClass('py-16');
  });
});

describe('ErrorState', () => {
  it('renders as an alert, distinct from EmptyState, with a retry button that retries', () => {
    const onRetry = vi.fn();
    withRouter(<ErrorState error={{}} onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('does not render a retry button when onRetry is omitted', () => {
    withRouter(<ErrorState error={{}} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('surfaces the server-provided error message over the fallback key', () => {
    const err = { response: { status: 500, data: { message: 'Database connection pool exhausted' } } };
    withRouter(<ErrorState error={err} fallbackKey="endpoints.toast.loadFailed" />);
    expect(screen.getByText('Database connection pool exhausted')).toBeInTheDocument();
  });

  it('surfaces a distinct "backend unreachable" message for a network error, not a generic fallback', () => {
    const networkErr = { request: {}, message: 'Network Error' };
    withRouter(<ErrorState error={networkErr} fallbackKey="endpoints.toast.loadFailed" />);
    // A down backend must look different from a normal load failure.
    expect(screen.queryByText('Failed to load data')).not.toBeInTheDocument();
    expect(screen.getByText(/network error/i)).toBeInTheDocument();
  });

  it('disables the retry button and shows a spinner label while retrying', () => {
    withRouter(<ErrorState error={{}} onRetry={() => {}} retrying />);
    const button = screen.getByRole('button', { name: /retrying/i });
    expect(button).toBeDisabled();
  });
});
