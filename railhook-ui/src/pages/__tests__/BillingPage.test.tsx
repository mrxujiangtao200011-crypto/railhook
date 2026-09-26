import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import '../../i18n';
import i18n from 'i18next';
import { renderPage } from '../../test/renderPage';
import type {
  InvoiceResponse,
  OrganizationBillingResponse,
  PlanResponse,
  UsageResponse,
} from '../../api/billing.api';

vi.mock('../../api/billing.api', () => ({
  billingApi: {
    listPlans: vi.fn(),
    getOrganizationBilling: vi.fn(),
    updateBillingInfo: vi.fn(),
    changePlan: vi.fn(),
    getUsage: vi.fn(),
    listInvoices: vi.fn(),
    createCheckout: vi.fn(),
    createPortal: vi.fn(),
    cancelSubscription: vi.fn(),
  },
}));

import BillingPage from '../BillingPage';
import { billingApi } from '../../api/billing.api';

const FREE: PlanResponse = {
  id: 'plan-free',
  name: 'free',
  displayName: 'Free',
  maxEventsPerMonth: 10000,
  maxEndpointsPerProject: 5,
  maxProjects: 3,
  maxMembers: 5,
  maxActiveTunnels: 1,
  rateLimitPerSecond: 10,
  maxRetentionDays: 7,
  features: { rules: false, replay: false },
  priceMonthlyCents: 0,
  priceYearlyCents: 0,
};

const ENTERPRISE: PlanResponse = {
  ...FREE,
  id: 'plan-enterprise',
  name: 'enterprise',
  displayName: 'Enterprise',
  maxEventsPerMonth: -1,
  maxEndpointsPerProject: -1,
  maxProjects: -1,
  maxMembers: -1,
  maxActiveTunnels: -1,
  rateLimitPerSecond: -1,
  maxRetentionDays: -1,
  features: { rules: true, replay: true },
  priceMonthlyCents: -1,
  priceYearlyCents: -1,
};

const use = (current: number, limit: number) => ({
  current,
  limit,
  percentUsed: limit > 0 ? Math.round((current / limit) * 100) : 0,
});

const USAGE: UsageResponse = {
  events: use(2500, 10000),
  endpoints: use(2, 5),
  projects: use(1, 3),
  members: use(1, 5),
  rateLimitPerSecond: 10,
  retentionDays: 7,
  periodStart: new Date('2026-08-01T00:00:00Z').toISOString(),
  periodEnd: new Date('2026-09-01T00:00:00Z').toISOString(),
};

const BILLING = {
  organizationId: 'org-1',
  plan: FREE,
  billingStatus: 'ACTIVE',
  billingEmail: 'billing@example.com',
  usage: USAGE,
} as unknown as OrganizationBillingResponse;

const INVOICE: InvoiceResponse = {
  id: 'inv-1',
  status: 'PAID',
  amountCents: 2900,
  currency: 'USD',
  planName: 'starter',
  periodStart: new Date('2026-08-01T00:00:00Z').toISOString(),
  periodEnd: new Date('2026-09-01T00:00:00Z').toISOString(),
  paidAt: new Date('2026-08-02T00:00:00Z').toISOString(),
  invoiceUrl: null,
};

function renderBilling() {
  return renderPage(<BillingPage />, { path: '/billing', initialEntry: '/billing' });
}

describe('BillingPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(billingApi.getOrganizationBilling).mockResolvedValue(BILLING);
    vi.mocked(billingApi.getUsage).mockResolvedValue(USAGE);
    vi.mocked(billingApi.listPlans).mockResolvedValue([FREE, ENTERPRISE]);
    vi.mocked(billingApi.listInvoices).mockResolvedValue([]);
  });

  it('shows the free plan price as $0 once, not "Free Free"', async () => {
    renderBilling();

    expect(await screen.findByText('$0/mo')).toBeInTheDocument();
    const heading = screen.getByRole('heading', { name: 'Free' });
    expect(heading.parentElement?.textContent).toBe('Free$0/mo');
  });

  it('ticks exactly the features the plan returns', async () => {
    vi.mocked(billingApi.getOrganizationBilling).mockResolvedValue({
      ...BILLING,
      plan: { ...FREE, features: { workflows: true, rules: true, replay: true, mTLS: true, tunnels: true } },
    } as unknown as OrganizationBillingResponse);
    renderBilling();

    await screen.findByText('$0/mo');
    expect(screen.queryAllByText(i18n.t('billing.notIncluded'))).toHaveLength(0);
    expect(screen.getAllByText(i18n.t('billing.included'))).toHaveLength(5);
  });

  it('renders an unlimited plan as unlimited, not as -1', async () => {
    renderBilling();

    await screen.findByText(/enterprise/i);
    expect(await screen.findAllByText(/unlimited/i)).not.toHaveLength(0);
    expect(screen.queryByText('-1')).toBeNull();
  });

  it('offers no plan picker when the current plan is the only one on offer', async () => {
    vi.mocked(billingApi.listPlans).mockResolvedValue([FREE]);
    renderBilling();

    await screen.findByText(/2[,.\s]?500/);
    await waitFor(() => expect(billingApi.listPlans).toHaveBeenCalled());
    expect(screen.queryByText(i18n.t('billing.availablePlans'))).toBeNull();
    expect(screen.queryByRole('group', { name: i18n.t('billing.billingInterval') })).toBeNull();
  });

  it('lists an invoice with its amount, and shows a paid one as paid, not as neither-here-nor-there', async () => {
    // Regression: the badge compared status to 'paid' while InvoiceStatus is upper case.
    vi.mocked(billingApi.listInvoices).mockResolvedValue([INVOICE]);
    renderBilling();

    const paid = await screen.findByText('Paid');
    const badge = paid.closest('[class*="bg-ok"], [class*="text-ok"], [data-kind]');
    expect(badge, 'a PAID invoice must not be badged as idle').not.toBeNull();
    expect(paid.closest('tr')?.textContent).toContain('$29 USD');
  });

  it('says why when the billing call fails, and offers a retry', async () => {
    vi.mocked(billingApi.getOrganizationBilling).mockRejectedValue({
      response: { status: 503, data: { message: 'Billing is not configured on this instance' } },
    });
    renderBilling();

    expect(await screen.findByRole('alert')).toHaveTextContent('Billing is not configured on this instance');
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('does not charge anyone by rendering', async () => {
    renderBilling();

    await screen.findAllByText(/free/i);
    expect(billingApi.createCheckout).not.toHaveBeenCalled();
    expect(billingApi.changePlan).not.toHaveBeenCalled();
    expect(billingApi.cancelSubscription).not.toHaveBeenCalled();
    expect(billingApi.createPortal).not.toHaveBeenCalled();
  });
});
