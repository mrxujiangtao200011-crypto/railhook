import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Button } from '../ui/button';

/** Slot needs exactly one child; a stray `false` child white-screened every <Button asChild>. */
describe('Button with asChild', () => {
  it('renders the child element instead of a button', () => {
    render(
      <Button asChild>
        <a href="/somewhere">Go</a>
      </Button>,
    );

    const link = screen.getByRole('link', { name: 'Go' });
    expect(link).toHaveAttribute('href', '/somewhere');
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('accepts a child that has several children of its own', () => {
    render(
      <Button asChild>
        <a href="/somewhere">
          <svg aria-hidden />
          Investigate
        </a>
      </Button>,
    );

    expect(screen.getByRole('link', { name: 'Investigate' })).toBeInTheDocument();
  });
});

describe('Button without asChild', () => {
  it.each([true, false])('shows a spinner and disables itself only while loading (isLoading=%s)', (isLoading) => {
    const { container } = render(<Button isLoading={isLoading}>Saving</Button>);

    expect(screen.getByRole('button', { name: /Saving/ }).hasAttribute('disabled')).toBe(isLoading);
    expect(container.querySelector('.animate-spin') !== null).toBe(isLoading);
  });
});
