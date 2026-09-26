import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '../../i18n';
import EmailSuggestion from '../EmailSuggestion';

describe('EmailSuggestion', () => {

  it('says an impossible ending cannot receive mail, not only that it looks odd', () => {
    render(<EmailSuggestion email="a@acme.con" onAccept={() => {}} />);
    expect(screen.getByRole('alert')).toHaveTextContent(/cannot receive mail/i);
  });

  it('is a quiet hint for a near miss of a popular domain', () => {
    render(<EmailSuggestion email="a@gmial.com" onAccept={() => {}} />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('a@gmail.com');
  });

  it('renders nothing for an address that looks right', () => {
    const { container } = render(<EmailSuggestion email="a@gmail.com" onAccept={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });
});
