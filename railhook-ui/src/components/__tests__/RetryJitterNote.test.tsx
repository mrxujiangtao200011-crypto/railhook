import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '../../i18n';
import RetryJitterNote from '../RetryJitterNote';

describe('RetryJitterNote', () => {
  it('states the jitter the worker actually applies', () => {
    const ladder = readFileSync(resolve(__dirname,
      '../../../../railhook-common/src/main/java/com/webhook/platform/common/retry/RetryLadder.java'), 'utf8');
    const [, low, span] = /jitterMultiplier = ([\d.]+) \+ ThreadLocalRandom\.current\(\)\.nextDouble\(([\d.]+)\)/.exec(ladder) ?? [];
    expect(low, 'the jitter expression in RetryLadder.java').toBeDefined();
    const percent = (x: number) => `${Math.round(x * 100)}%`;

    render(<RetryJitterNote />);
    expect(screen.getByText(new RegExp(`${percent(Number(low))} and ${percent(Number(low) + Number(span))}`))).toBeInTheDocument();
  });

  it('sits under every retry ladder the dashboard draws', () => {
    for (const file of ['components/CreateSubscriptionModal.tsx', 'pages/ConnectionSetupPage.tsx', 'pages/IncomingSourceDetailPage.tsx']) {
      expect(readFileSync(resolve(__dirname, '../..', file), 'utf8'), file).toContain('<RetryJitterNote');
    }
  });
});
