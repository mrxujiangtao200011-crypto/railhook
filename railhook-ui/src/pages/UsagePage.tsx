import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useProject, useUsageStats } from '../api/queries';
import { billingApi, type ResourceUsage } from '../api/billing.api';
import { formatDate } from '../lib/date';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import { ErrorState } from '../components/EmptyState';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { cn } from '../lib/utils';
import {
  ChartCard, OutcomeChart, STATUS_TEXT, ShareBar, formatCompact, formatRate,
  outcomeLegend, quotaKind, type ShareSegment,
} from '../components/charts';
import { Ledger, LedgerRow, Segmented } from '../components/port/p1/kit';

const WINDOWS = [7, 30, 90] as const;

const NO_QUOTA: ResourceUsage = { current: 0, limit: 0, percentUsed: 0 };

function QuotaRow({ label, usage }: { label: string; usage: ResourceUsage }) {
  const { t } = useTranslation();
  const unlimited = !Number.isFinite(usage.limit) || usage.limit <= 0;
  const kind = unlimited ? 'within' : quotaKind(usage.percentUsed);
  const filled = unlimited ? 0 : Math.min(Math.max(usage.percentUsed, 0), 100);
  return (
    <div className="border-b border-rail py-3">
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="min-w-0 truncate text-muted-foreground">{label}</span>
        <span className={cn('whitespace-nowrap tabular-nums', kind === 'over' && 'text-halt', kind === 'approaching' && 'text-retry')}>
          {formatCompact(usage.current)}
          <span className="ml-1 text-[12px] text-muted-foreground">
            {unlimited
              ? t('usage.quota.unlimited')
              : t('usage.quota.ofLimit', { limit: formatCompact(usage.limit), percent: formatRate(usage.percentUsed) })}
          </span>
        </span>
      </div>
      {!unlimited && (
        <div className="mt-2 h-[3px] w-full bg-secondary" aria-hidden>
          <div
            className={cn('h-full', kind === 'over' ? 'bg-halt' : kind === 'approaching' ? 'bg-retry' : 'bg-foreground/60')}
            style={{ width: `${filled}%` }}
          />
        </div>
      )}
    </div>
  );
}

export default function UsagePage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const [days, setDays] = useState<number>(30);

  const { data: project } = useProject(projectId);
  const {
    data: usage, isLoading, isError, error, isFetching, refetch,
  } = useUsageStats(projectId, days);

  // Same key BillingPage reads, so the two screens never disagree on quota.
  const { data: quota } = useQuery({
    queryKey: ['billing', 'usage'],
    queryFn: billingApi.getUsage,
  });

  const current = usage?.current;
  const history = useMemo(() => usage?.history ?? [], [usage]);

  const dailySeries = useMemo(
    () => history
      .slice()
      .sort((a, b) => a.date.localeCompare(b.date))
      .map((d) => ({
        timestamp: d.date,
        success: d.successfulDeliveries ?? 0,
        failed: (d.failedDeliveries ?? 0) + (d.dlqCount ?? 0),
      })),
    [history]
  );

  const outcomeLabels = {
    success: t('usage.traffic.delivered'),
    failed: t('usage.traffic.failed'),
  };

  const mix: ShareSegment[] = [
    { key: 'delivered', label: t('usage.mix.delivered'), value: current?.successfulDeliveries ?? 0, token: 'ok' },
    { key: 'inFlight', label: t('usage.mix.inFlight'), value: current?.pendingDeliveries ?? 0, token: 'idle' },
    { key: 'failed', label: t('usage.mix.failed'), value: current?.failedDeliveries ?? 0, token: 'retry' },
    { key: 'abandoned', label: t('usage.mix.abandoned'), value: current?.dlqDeliveries ?? 0, token: 'halt' },
  ];
  const mixTotal = Math.max(current?.totalDeliveries ?? 0, 1);

  if (isLoading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <SkeletonCards count={2} height="h-[280px]" cols="grid-cols-1" />
      </PageSkeleton>
    );
  }

  if (isError) {
    return (
      <div className="p-4 lg:p-8">
        <PageHeader eyebrow={project?.name} title={t('usage.title')} description={t('usage.description')} />
        <ErrorState error={error} fallbackKey="usage.loadFailed" onRetry={() => refetch()} />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-8">
      <PageHeader
        eyebrow={project?.name}
        title={t('usage.title')}
        description={t('usage.description')}
      />

      <div className="mb-8">
        <Segmented
          label={t('usage.periodLabel')}
          value={String(days)}
          onChange={(v) => setDays(Number(v))}
          options={WINDOWS.map((w) => ({ value: String(w), label: t(`usage.periods.${w}d`) }))}
        />
      </div>

      <div className="space-y-12">
        <section className="min-w-0">
          <h3 className="text-[15px] font-medium leading-tight">{t('usage.quota.title')}</h3>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {quota
              ? t('usage.quota.desc', { from: formatDate(quota.periodStart), to: formatDate(quota.periodEnd) })
              : t('usage.quota.descPending')}
          </p>
          <div className="mt-4 grid gap-x-12 border-t border-rail md:grid-cols-2">
            <QuotaRow label={t('usage.quota.events')} usage={quota?.events ?? NO_QUOTA} />
            <QuotaRow label={t('usage.quota.endpoints')} usage={quota?.endpoints ?? NO_QUOTA} />
            <QuotaRow label={t('usage.quota.projects')} usage={quota?.projects ?? NO_QUOTA} />
            <QuotaRow label={t('usage.quota.members')} usage={quota?.members ?? NO_QUOTA} />
          </div>
        </section>

        <div className="grid gap-x-12 gap-y-12 xl:grid-cols-[minmax(0,1fr)_20rem]">
          <ChartCard
            title={t('usage.traffic.title')}
            description={t('usage.traffic.desc')}
            eyebrow={t(`usage.periods.${days}d`)}
            legend={outcomeLegend(outcomeLabels)}
            bodyClass="h-[280px]"
            isRefetching={isFetching}
            isEmpty={dailySeries.length === 0}
            emptyLabel={t('usage.traffic.empty')}
          >
            <OutcomeChart
              data={dailySeries}
              labels={outcomeLabels}
              formatTick={formatDate}
              formatStamp={formatDate}
            />
          </ChartCard>

          <div className="min-w-0 space-y-10">
            <section className="border-t border-rail pt-4">
              <h3 className="text-sm font-medium leading-tight">{t('usage.mix.title')}</h3>
              <p className="mb-4 mt-0.5 text-xs text-muted-foreground">{t('usage.mix.desc')}</p>
              <ShareBar segments={mix} total={mixTotal} />
              <p className="mt-4 font-mono text-xs text-muted-foreground">
                {t('usage.mix.total', { total: formatCompact(current?.totalDeliveries ?? 0) })}
              </p>
            </section>
            <Ledger>
              <LedgerRow label={t('usage.tiles.events')}>{formatCompact(current?.totalEvents ?? 0)}</LedgerRow>
              <LedgerRow label={t('usage.tiles.incoming')}>{formatCompact(current?.totalIncomingEvents ?? 0)}</LedgerRow>
              <LedgerRow label={t('usage.resources.endpoints')}>{formatCompact(current?.activeEndpoints ?? 0)}</LedgerRow>
              <LedgerRow label={t('usage.resources.alertRules')}>{formatCompact(current?.activeAlertRules ?? 0)}</LedgerRow>
            </Ledger>
          </div>
        </div>

        <section className="min-w-0 border-t border-rail pt-4">
          <h3 className="text-sm font-medium leading-tight">{t('usage.history.title')}</h3>
          <p className="mt-0.5 text-xs text-muted-foreground">{t('usage.history.desc')}</p>
          {history.length === 0 ? (
            <p className="py-6 text-sm text-muted-foreground">{t('usage.history.empty')}</p>
          ) : (
            <Table className="mt-3 text-[13px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="px-2">{t('usage.history.date')}</TableHead>
                  <TableHead className="px-2 text-right">{t('usage.history.events')}</TableHead>
                  <TableHead className="px-2 text-right">{t('usage.history.deliveries')}</TableHead>
                  <TableHead className="px-2 text-right">{t('usage.history.success')}</TableHead>
                  <TableHead className="px-2 text-right">{t('usage.history.failed')}</TableHead>
                  <TableHead className="px-2 text-right">{t('usage.history.dlq')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {history.map((day) => (
                  <TableRow key={day.date} className={cn(day.dlqCount > 0 && 'bg-halt-soft/40')}>
                    <TableCell className="px-2 font-mono text-[12px]">{day.date}</TableCell>
                    <TableCell className="px-2 text-right tabular-nums">{formatCompact(day.eventsCount)}</TableCell>
                    <TableCell className="px-2 text-right tabular-nums">{formatCompact(day.deliveriesCount)}</TableCell>
                    <TableCell className="px-2 text-right tabular-nums">{formatCompact(day.successfulDeliveries)}</TableCell>
                    <TableCell className={cn('px-2 text-right tabular-nums', day.failedDeliveries > 0 ? STATUS_TEXT.retry : 'text-muted-foreground')}>
                      {formatCompact(day.failedDeliveries)}
                    </TableCell>
                    <TableCell className={cn('px-2 text-right tabular-nums', day.dlqCount > 0 ? STATUS_TEXT.halt : 'text-muted-foreground')}>
                      {formatCompact(day.dlqCount)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </section>
      </div>
    </div>
  );
}
