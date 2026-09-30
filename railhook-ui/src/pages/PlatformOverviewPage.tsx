import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import PageHeader from '../components/PageHeader';
import { SkeletonTable } from '../components/PageSkeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { usePlatformOrganizations, usePlatformOverview } from '../api/queries';
import type { AdminOrganization, PlatformActivation, PlatformOverview } from '../api/platformAdmin.api';
import { formatDateTimeCompact, formatNumber, formatRelativeTime, formatTime } from '../lib/date';
import { cn } from '../lib/utils';
import {
  DailyBars, EmailText, OrganizationLink, PLATFORM_TABLE, PLATFORM_TABLE_HEADER, PlatformErrorState, PlatformScope, SignInMethods, VerifiedBadge,
} from './platformAdminParts';

const FAILURE_ATTENTION = 0.01;
const VISIBLE_ITEMS = 3;

function percent(value: number, lang: string) {
  return new Intl.NumberFormat(lang, value > 0 && value < 0.1 ? { style: 'percent', maximumSignificantDigits: 2 } : { style: 'percent', maximumFractionDigits: 0 }).format(value);
}

interface Item { key: string; tone: 'halt' | 'retry'; title: string; detail?: string; to?: string }

function Attention({ overview, suspended }: { overview: PlatformOverview; suspended: AdminOrganization[] }) {
  const { t, i18n } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const total = overview.deliveriesSucceeded24h + overview.deliveriesFailed24h;
  const rate = total > 0 ? overview.deliveriesFailed24h / total : 0;
  const items: Item[] = [];
  if (rate >= FAILURE_ATTENTION) {
    items.push({
      key: 'failed',
      tone: 'halt',
      title: t('platformAdmin.overview.attention.failed', { count: overview.deliveriesFailed24h, value: formatNumber(overview.deliveriesFailed24h) }),
      detail: t('platformAdmin.overview.attention.failedDetail', { rate: percent(rate, i18n.language), total: formatNumber(total) }),
    });
  }
  for (const org of suspended) {
    items.push({
      key: org.id,
      tone: 'halt',
      title: t('platformAdmin.overview.attention.suspended', { name: org.name }),
      detail: [org.suspensionReason, org.suspendedAt && formatRelativeTime(org.suspendedAt)].filter(Boolean).join(' · '),
      to: `/admin/platform/organizations/${org.id}`,
    });
  }
  if (overview.organizationsNearQuota > 0) {
    items.push({
      key: 'quota',
      tone: 'retry',
      title: t('platformAdmin.overview.attention.nearQuota', { count: overview.organizationsNearQuota }),
      detail: t('platformAdmin.overview.kpi.nearQuotaHint'),
      to: '/admin/platform/organizations',
    });
  }

  if (items.length === 0) {
    const fresh = overview.events30d === 0 && overview.organizations <= 1;
    return (
      <section>
        <p className="flex items-center gap-2.5 text-[15px]">
          <span aria-hidden className={cn('h-2 w-2 rounded-full', fresh ? 'bg-idle' : 'bg-ok')} />
          {t(fresh ? 'platformAdmin.overview.attention.fresh' : 'platformAdmin.overview.attention.clear')}
        </p>
        <p className="mt-1 pl-[18px] text-[13px] text-muted-foreground">
          {t(fresh ? 'platformAdmin.overview.attention.freshDetail' : 'platformAdmin.overview.attention.clearDetail')}
        </p>
      </section>
    );
  }

  const shown = expanded ? items : items.slice(0, VISIBLE_ITEMS);
  return (
    <section aria-labelledby="platform-attention">
      <h3 id="platform-attention" className="text-[22px] font-normal leading-tight tracking-[-0.015em]">
        {t('platformAdmin.overview.attention.title', { count: items.length })}
      </h3>
      <ul className="mt-4 border-t border-rail">
        {shown.map((item) => (
          <li key={item.key} className="flex items-start gap-3 border-b border-rail py-3">
            <span aria-hidden className={cn('mt-[7px] h-2 w-2 flex-shrink-0 rounded-full', item.tone === 'halt' ? 'bg-halt' : 'bg-retry')} />
            <div className="min-w-0 flex-1">
              <p className="break-words text-[15px] leading-snug">{item.title}</p>
              {item.detail && <p className="mt-0.5 break-words text-[13px] text-muted-foreground">{item.detail}</p>}
            </div>
            {item.to && (
              <Link to={item.to} className="-my-1.5 flex min-h-[44px] flex-shrink-0 items-center px-1 text-[13px] underline decoration-rail underline-offset-4 hover:decoration-foreground">
                {t('platformAdmin.overview.attention.review')}
              </Link>
            )}
          </li>
        ))}
      </ul>
      {items.length > VISIBLE_ITEMS && (
        <button type="button" onClick={() => setExpanded((v) => !v)} className="mt-1 min-h-[44px] text-[13px] text-muted-foreground hover:text-foreground">
          {expanded ? t('platformAdmin.overview.attention.showLess') : t('platformAdmin.overview.attention.showMore', { count: items.length - VISIBLE_ITEMS })}
        </button>
      )}
    </section>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-rail py-2.5 text-sm">
      <dt className="flex-shrink-0 text-muted-foreground">{label}</dt>
      <dd className="min-w-0 text-right tabular-nums">{children}</dd>
    </div>
  );
}

function Secondary({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={cn('block text-[13px] text-muted-foreground', className)}>{children}</span>;
}

function Ledger({ overview }: { overview: PlatformOverview }) {
  const { t, i18n } = useTranslation();
  const total = overview.deliveriesSucceeded24h + overview.deliveriesFailed24h;
  const rate = total > 0 ? overview.deliveriesFailed24h / total : 0;
  return (
    <dl className="border-t border-rail">
      <Row label={t('platformAdmin.overview.ledger.events')}>
        {t('platformAdmin.overview.ledger.today', { value: formatNumber(overview.eventsToday) })}
        <Secondary>{t('platformAdmin.overview.kpi.events30d', { total: formatNumber(overview.events30d) })}</Secondary>
      </Row>
      <Row label={t('platformAdmin.overview.ledger.deliveries')}>
        {t('platformAdmin.overview.ledger.delivered', { value: formatNumber(overview.deliveriesSucceeded24h) })}
        <Secondary className={cn(rate >= FAILURE_ATTENTION && 'text-halt')}>
          {t('platformAdmin.overview.ledger.failed', { value: formatNumber(overview.deliveriesFailed24h) })}
          {overview.deliveriesFailed24h > 0 && ` · ${percent(rate, i18n.language)}`}
        </Secondary>
      </Row>
      <Row label={t('platformAdmin.overview.ledger.signups')}>
        {t('platformAdmin.overview.ledger.today', { value: formatNumber(overview.signupsToday) })}
        <Secondary>{t('platformAdmin.overview.kpi.signupsPeriods', { week: formatNumber(overview.signups7d), month: formatNumber(overview.signups30d) })}</Secondary>
      </Row>
      <Row label={t('platformAdmin.overview.kpi.organizations')}>
        <span>{formatNumber(overview.organizations)}</span>
        {overview.suspendedOrganizations > 0 && (
          <Secondary className="text-halt">{t('platformAdmin.overview.kpi.suspended', { count: overview.suspendedOrganizations })}</Secondary>
        )}
      </Row>
      <Row label={t('platformAdmin.overview.kpi.users')}>{formatNumber(overview.users)}</Row>
      <Row label={t('platformAdmin.overview.kpi.nearQuota')}>
        <span className={cn(overview.organizationsNearQuota > 0 && 'text-retry')}>{formatNumber(overview.organizationsNearQuota)}</span>
      </Row>
      <Row label={t('platformAdmin.overview.kpi.activeTunnels')}>{formatNumber(overview.activeTunnels)}</Row>
    </dl>
  );
}

function Activation({ activation }: { activation: PlatformActivation }) {
  const { t, i18n } = useTranslation();
  const steps: { key: string; value: number; previous?: number }[] = [
    { key: 'signups', value: activation.signups },
    { key: 'verified', value: activation.verified, previous: activation.signups },
    { key: 'organizations', value: activation.organizations },
    { key: 'withProject', value: activation.withProject, previous: activation.organizations },
    { key: 'withEvent', value: activation.withEvent, previous: activation.withProject },
  ];
  const title = t('platformAdmin.overview.activation.title');
  return (
    <section>
      <h3 className="text-[13px] text-muted-foreground">{title}</h3>
      <ul aria-label={title} className="mt-3 grid grid-cols-2 gap-x-6 gap-y-5 border-t border-rail pt-3 sm:grid-cols-5">
        {steps.map((step) => (
          <li key={step.key} className="min-w-0">
            <p className="text-[13px] leading-snug text-muted-foreground sm:min-h-[2.5em]">{t(`platformAdmin.overview.activation.${step.key}`)}</p>
            <p className="mt-1 whitespace-nowrap text-[15px] tabular-nums">
              {formatNumber(step.value)}
              {step.previous !== undefined && step.previous > 0 && (
                <span className="ml-2 text-[13px] text-muted-foreground">{percent(step.value / step.previous, i18n.language)}</span>
              )}
            </p>
          </li>
        ))}
      </ul>
    </section>
  );
}

export default function PlatformOverviewPage() {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch, isRefetching } = usePlatformOverview();
  const { data: suspended } = usePlatformOrganizations(0, 5, { suspendedOnly: true });

  return (
    <div className="p-4 lg:p-8">
      <PageHeader
        eyebrow={data
          ? <span className="font-mono">{t('platformAdmin.overview.generatedAt', { time: formatTime(data.generatedAt) })}</span>
          : t('platformAdmin.eyebrow')}
        description={t('platformAdmin.overview.description')}
      />
      <PlatformScope />

      {isError ? (
        <PlatformErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      ) : isLoading || !data ? (
        <SkeletonTable rows={6} />
      ) : (
        <>
          <div className="grid gap-x-16 gap-y-10 xl:grid-cols-[minmax(0,1fr)_20rem]">
            <div className="min-w-0 space-y-10">
              <Attention overview={data} suspended={data.suspendedOrganizations > 0 ? suspended?.content ?? [] : []} />
              <div className="grid gap-x-10 gap-y-8 sm:grid-cols-2">
                <DailyBars
                  label={t('platformAdmin.overview.daily.events')}
                  days={data.daily30d.map((d) => ({ date: d.date, value: d.events }))}
                />
                <DailyBars
                  label={t('platformAdmin.overview.daily.signups')}
                  days={data.daily30d.map((d) => ({ date: d.date, value: d.signups }))}
                />
              </div>
            </div>
            <div className="min-w-0 max-xl:max-w-xl xl:row-span-2">
              <Ledger overview={data} />
            </div>
            <div className="min-w-0">
              <Activation activation={data.activation30d} />
            </div>
          </div>

          <section className="mt-14">
            <h3 className="mb-3 text-[15px] font-medium">{t('platformAdmin.overview.recentSignups')}</h3>
            {data.recentSignups.length === 0 ? (
              <p className="border-t border-rail py-8 text-[13px] text-muted-foreground">{t('platformAdmin.overview.noSignups')}</p>
            ) : (
              <Table className={PLATFORM_TABLE}>
                <TableHeader className={PLATFORM_TABLE_HEADER}>
                  <TableRow>
                    <TableHead>{t('platformAdmin.columns.account')}</TableHead>
                    <TableHead>{t('platformAdmin.columns.organization')}</TableHead>
                    <TableHead>{t('platformAdmin.columns.signIn')}</TableHead>
                    <TableHead>{t('platformAdmin.columns.verified')}</TableHead>
                    <TableHead className="text-right">{t('platformAdmin.columns.created')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {data.recentSignups.map((signup) => (
                    <TableRow key={signup.userId}>
                      <TableCell>
                        <EmailText email={signup.email} />
                        {signup.fullName && <p className="max-w-[13rem] truncate text-xs text-muted-foreground max-sm:max-w-full" title={signup.fullName}>{signup.fullName}</p>}
                      </TableCell>
                      <TableCell>
                        {signup.organizationId && signup.organizationName
                          ? <OrganizationLink id={signup.organizationId} name={signup.organizationName} />
                          : '—'}
                      </TableCell>
                      <TableCell><SignInMethods methods={signup.signInMethods} /></TableCell>
                      <TableCell><VerifiedBadge verified={signup.emailVerified} /></TableCell>
                      <TableCell className="whitespace-nowrap text-right text-[13px] text-muted-foreground" title={formatDateTimeCompact(signup.createdAt)}>
                        {formatRelativeTime(signup.createdAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </section>
        </>
      )}
    </div>
  );
}
