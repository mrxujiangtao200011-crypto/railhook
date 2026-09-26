import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import { PrivacyPage, TermsPage } from '../LegalPage';

describe('legal pages', () => {
  it('the privacy policy renders, with the statements Google requires of it', async () => {
    renderPage(<PrivacyPage />, { path: '/privacy', initialEntry: '/privacy' });

    expect(await screen.findByRole('heading', { level: 1, name: 'Privacy Policy' })).toBeInTheDocument();
    const text = document.body.textContent ?? '';
    expect(text).toMatch(/Limited Use/);
    expect(text).toMatch(/openid, email and profile/);
    expect(text).toMatch(/support@railhook\.io/);
    expect(text).toMatch(/7 days/);
    expect(screen.getByRole('link', { name: 'Terms of Service' })).toHaveAttribute('href', '/terms');
  });

  it('the terms of service render, with the Free plan limits', async () => {
    renderPage(<TermsPage />, { path: '/terms', initialEntry: '/terms' });

    expect(await screen.findByRole('heading', { level: 1, name: 'Terms of Service' })).toBeInTheDocument();
    const text = document.body.textContent ?? '';
    expect(text).toMatch(/10,000 events per month/);
    expect(text).toMatch(/MIT license/);
    expect(text).toMatch(/laws of Ukraine/);
    expect(screen.getByRole('link', { name: 'Privacy Policy' })).toHaveAttribute('href', '/privacy');
  });
});
