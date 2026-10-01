import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Download, Loader2, RefreshCw } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { useAnalytics, queryKeys } from '../api/queries';
import { dashboardApi, type AnalyticsData } from '../api/dashboard.api';
import { formatDateTimeShort, formatTime, toLocalDatetime } from '../lib/date';
import { PRESETS, customRange, rangeQuery, type AnalyticsRange } from '../lib/analyticsRange';
import { showApiError } from '../lib/toast';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { cn } from '../lib/utils';
import { Ledger, LedgerRow, Segmented } from '../components/port/p1/kit';
import {
  BarRankChart, ChartCard, OutcomeChart, STATUS_TEXT, TrendChart,
  formatCompact, formatMs, formatRate, kindOfEndpointStatus, kindOfSuccessRate, outcomeLegend,
  share, type RankDatum,
} from '../components/charts';

const EMPTY_OVERVIEW: AnalyticsData['overview'] = {
  totalEvents: 0, totalDeliveries: 0, successfulDeliveries: 0, failedDeliveries: 0,
  successRate: 0, avgLatencyMs: 0, p50LatencyMs: 0, p95LatencyMs: 0, p99LatencyMs: 0,
  eventsPerSecond: 0, deliveriesPerSecond: 0,
};

const EMPTY_PERCENTILES: AnalyticsData['latencyPercentiles'] = {
  p50: 0, p75: 0, p90: 0, p95: 0, p99: 0, max: 0,
};

export default function AnalyticsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const [range, setRange] = useState<AnalyticsRange>({ period: '24h' });
  const [editingCustom, setEditingCustom] = useState(false);
  const [draftFrom, setDraftFrom] = useState(() => toLocalDatetime(new Date(Date.now() - 86_400_000).toISOString()));
  const [draftTo, setDraftTo] = useState(() => toLocalDatetime(new Date().toISOString()));
  const [exporting, setExporting] = useState(false);
  const qc = useQueryClient();

  const {
    data: analytics, isLoading, isError, error, isFetching, refetch,
  } = useAnalytics(projectId, range);

  const period = 'period' in range
    ? range.period
    : `${formatDateTimeShort(range.from)} – ${formatDateTimeShort(range.to)}`;
  const draftRange = customRange(draftFrom, draftTo);
  const customActive = editingCustom || !('period' in range);

  const refresh = () => {
    if (projectId) qc.invalidateQueries({ queryKey: queryKeys.dashboard.analytics(projectId, rangeQuery(range)) });
  };

  const exportCsv = async () => {
    if (!projectId) return;
    setExporting(true);
    try {
      const { blob, filename } = await dashboardApi.exportAnalyticsCsv(projectId, range);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename ?? 'analytics.csv';
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      showApiError(err, 'analytics.export.failed');
    } finally {
      setExporting(false);
    }
  };

  // Defaults everywhere: a window with no traffic comes back sparse.
  const overview = analytics?.overview ?? EMPTY_OVERVIEW;
  const percentiles = analytics?.latencyPercentiles ?? EMPTY_PERCENTILES;
  const endpointPerformance = analytics?.endpointPerformance ?? [];

  const outcomeSeries = useMemo(
    () => (analytics?.deliveryTimeSeries ?? []).map((p) => ({
      timestamp: p.timestamp,
      success: p.success ?? 0,
      failed: p.failed ?? 0,
    })),
    [analytics]
  );

  const latencySeries = useMemo(
    () => (analytics?.latencyTimeSeries ?? []).filter((p) => (p.avgLatencyMs ?? 0) > 0),
    [analytics]
  );

  const eventTypeRows: RankDatum[] = useMemo(
    () => (analytics?.eventTypeBreakdown ?? [])
      .slice()
      .sort((a, b) => b.count - a.count)
      .slice(0, 8)
      .map((e) => ({ key: e.eventType, label: e.eventType, value: e.count })),
    [analytics]
  );

  const percentileRows: RankDatum[] = useMemo(
    () => ([
      ['p50', percentiles.p50], ['p75', percentiles.p75], ['p90', percentiles.p90],
      ['p95', percentiles.p95], ['p99', percentiles.p99],
    ] as const).map(([label, value]) => ({ key: label, label, value: value ?? 0 })),
    [percentiles]
  );

  const outcomeLabels = {
    success: t('analytics.outcome.delivered'),
    failed: t('analytics.outcome.failed'),
  };

  const presetValue = customActive ? 'custom' : ('period' in range ? range.period : 'custom');
  const picker = (
    <div className="mb-8 space-y-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Segmented
          label={t('analytics.periodLabel')}
          value={presetValue}
          onChange={(v) => {
            if (v === 'custom') { setEditingCustom(true); return; }
            setEditingCustom(false);
            setRange({ period: v as (typeof PRESETS)[number] });
          }}
          options={[
            ...PRESETS.map((p) => ({ value: p as string, label: t(`analytics.periods.${p}`) })),
            { value: 'custom', label: t('analytics.custom') },
          ]}
        />
        {editingCustom && (
          <div className="flex flex-wrap items-center gap-2 max-sm:w-full">
            <Input
              type="datetime-local"
              aria-label={t('analytics.from')}
              value={draftFrom}
              onChange={(e) => setDraftFrom(e.target.value)}
              className="w-auto font-mono text-[13px] max-sm:w-full"
            />
            <Input
              type="datetime-local"
              aria-label={t('analytics.to')}
              value={draftTo}
              onChange={(e) => setDraftTo(e.target.value)}
              className="w-auto font-mono text-[13px] max-sm:w-full"
            />
            <Button size="sm" disabled={!draftRange} onClick={() => draftRange && setRange(draftRange)}>
              {t('analytics.apply')}
            </Button>
          </div>
        )}
      </div>
      <p className="text-xs text-muted-foreground">{t('analytics.export.hint')}</p>
    </div>
  );

  const actions = (
    <div className="flex gap-2">
      <Button variant="outline" size="sm" onClick={exportCsv} disabled={exporting}>
        {exporting ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Download className="h-4 w-4" aria-hidden />}
        {t('analytics.export.csv')}
      </Button>
      <Button variant="outline" size="sm" onClick={refresh} disabled={isFetching}>
        <RefreshCw className={cn('h-4 w-4', isFetching && 'animate-spin')} aria-hidden />
        {t('analytics.refresh')}
      </Button>
    </div>
  );

  if (isLoading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <SkeletonCards count={2} height="h-[300px]" cols="grid-cols-1" />
      </PageSkeleton>
    );
  }

  if (isError || !analytics) {
    return (
      <div className="p-4 lg:p-8">
        <PageHeader eyebrow={period} description={t('analytics.subtitle')} />
        {picker}
        <ErrorState error={error} fallbackKey="analytics.loadFailed" onRetry={() => refetch()} />
      </div>
    );
  }

  const hasDeliveries = overview.totalDeliveries > 0;

  return (
    <div className="p-4 lg:p-8">
      <PageHeader
        eyebrow={period}
        description={t('analytics.subtitle')}
        actions={actions}
      />

      {picker}

      <dl className="mb-10 flex flex-wrap items-baseline gap-x-8 gap-y-2 border-y border-rail py-3 text-sm">
        <div className="flex items-baseline gap-2">
          <dt className="text-muted-foreground">{t('analytics.successRate')}</dt>
          <dd className={cn('tabular-nums', hasDeliveries && STATUS_TEXT[kindOfSuccessRate(overview.successRate, true)])}>
            {hasDeliveries ? `${formatRate(overview.successRate)}%` : '—'}
          </dd>
          <dd className="text-[12px] text-muted-foreground">
            {t('analytics.tiles.successRateHint', {
              delivered: formatCompact(overview.successfulDeliveries),
              total: formatCompact(overview.totalDeliveries),
            })}
          </dd>
        </div>
        <div className="flex items-baseline gap-2">
          <dt className="text-muted-foreground">{t('analytics.failed')}</dt>
          <dd className={cn('tabular-nums', overview.failedDeliveries > 0 && 'text-halt')}>
            {formatCompact(overview.failedDeliveries)}
            {hasDeliveries && <span className="ml-1.5 text-[12px] text-muted-foreground">{formatRate(share(overview.failedDeliveries, overview.totalDeliveries))}%</span>}
          </dd>
        </div>
        <div className="flex items-baseline gap-2">
          <dt className="text-muted-foreground">{t('analytics.avgLatency')}</dt>
          <dd className="font-mono text-[13px]">{hasDeliveries ? formatMs(overview.avgLatencyMs) : '—'}</dd>
        </div>
        <div className="flex items-baseline gap-2">
          <dt className="text-muted-foreground">p95</dt>
          <dd className="font-mono text-[13px]">{hasDeliveries ? formatMs(overview.p95LatencyMs) : '—'}</dd>
        </div>
        <div className="flex items-baseline gap-2">
          <dt className="text-muted-foreground">{t('analytics.throughput')}</dt>
          <dd className="font-mono text-[13px]">{overview.deliveriesPerSecond.toFixed(2)}</dd>
          <dd className="text-[12px] text-muted-foreground">{t('analytics.deliveriesPerSec')}</dd>
        </div>
      </dl>

      <div className="space-y-12">
        <ChartCard
          title={t('analytics.outcome.title')}
          description={t('analytics.outcome.desc')}
          legend={outcomeLegend(outcomeLabels)}
          bodyClass="h-[300px]"
          isRefetching={isFetching}
          isEmpty={outcomeSeries.length === 0}
          emptyLabel={t('analytics.noDeliveryData')}
        >
          <OutcomeChart
            data={outcomeSeries}
            labels={outcomeLabels}
            formatTick={formatTime}
            formatStamp={formatDateTimeShort}
          />
        </ChartCard>

        <div className="grid gap-x-12 gap-y-12 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
          <ChartCard
            title={t('analytics.responseLatency')}
            description={t('analytics.responseLatencyDesc')}
            bodyClass="h-[240px]"
            isRefetching={isFetching}
            isEmpty={latencySeries.length === 0}
            emptyLabel={t('analytics.noLatencyData')}
          >
            <TrendChart
              data={latencySeries as unknown as Record<string, unknown>[]}
              dataKey="avgLatencyMs"
              seriesLabel={t('analytics.latencySeries')}
              formatTick={formatTime}
              formatStamp={formatDateTimeShort}
              formatValue={formatMs}
            />
          </ChartCard>

          <section className="min-w-0 border-t border-rail pt-4">
            <h3 className="text-sm font-medium leading-tight">{t('analytics.latencyPercentiles')}</h3>
            <p className="mt-0.5 text-xs text-muted-foreground">{t('analytics.latencyPercentilesDesc')}</p>
            {hasDeliveries ? (
              <Ledger className="mt-4">
                {percentileRows.map((row) => (
                  <LedgerRow key={row.key} label={<span className="font-mono text-[12px]">{row.label}</span>}>
                    <span className="font-mono text-[13px]">{formatMs(row.value)}</span>
                  </LedgerRow>
                ))}
                <LedgerRow label={<span className="font-mono text-[12px]">max</span>}>
                  <span className="font-mono text-[13px]">{formatMs(percentiles.max ?? 0)}</span>
                </LedgerRow>
              </Ledger>
            ) : (
              <p className="mt-4 text-[13px] text-muted-foreground">{t('analytics.noLatencyPercentiles')}</p>
            )}
          </section>
        </div>

        <ChartCard
          title={t('analytics.eventTypes')}
          description={t('analytics.eventTypesDesc')}
          bodyClass="h-[260px]"
          isRefetching={isFetching}
          isEmpty={eventTypeRows.length === 0}
          emptyLabel={t('analytics.noEventsRecorded')}
        >
          <BarRankChart
            data={eventTypeRows}
            seriesLabel={t('analytics.eventTypesSeries')}
            categoryWidth={148}
          />
        </ChartCard>

        <section className="min-w-0 border-t border-rail pt-4">
          <h3 className="text-sm font-medium leading-tight">{t('analytics.endpointPerformance')}</h3>
          <p className="mt-0.5 text-xs text-muted-foreground">{t('analytics.endpointPerformanceDesc')}</p>
          {endpointPerformance.length === 0 ? (
            <p className="py-6 text-sm text-muted-foreground">{t('analytics.noEndpointData')}</p>
          ) : (
            <Table className="mt-3 text-[13px]">
              <TableHeader>
                <TableRow>
                  <TableHead className="px-2">{t('analytics.epColumns.endpoint')}</TableHead>
                  <TableHead className="w-[130px] px-2">{t('analytics.epColumns.status')}</TableHead>
                  <TableHead className="w-[110px] px-2 text-right">{t('analytics.epColumns.deliveries')}</TableHead>
                  <TableHead className="w-[100px] px-2 text-right">{t('analytics.epColumns.success')}</TableHead>
                  <TableHead className="w-[100px] px-2 text-right">{t('analytics.epColumns.latency')}</TableHead>
                  <TableHead className="w-[90px] px-2 text-right">p95</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {endpointPerformance.map((ep) => {
                  const rateKind = kindOfSuccessRate(ep.successRate, ep.enabled);
                  return (
                    <TableRow key={ep.endpointId} className={cn(ep.status === 'FAILING' && 'bg-halt-soft/40')}>
                      <TableCell className="px-2 sm:max-w-0">
                        <Link
                          to={`/admin/projects/${projectId}/deliveries?endpointId=${ep.endpointId}`}
                          className="block truncate font-mono text-[12px] hover:underline max-sm:whitespace-normal max-sm:break-all"
                          title={ep.url}
                        >
                          {ep.url}
                        </Link>
                      </TableCell>
                      <TableCell className="px-2">
                        <StatusBadge kind={kindOfEndpointStatus(ep.status)} label={t(`analytics.endpointStatus.${ep.status}`)} />
                      </TableCell>
                      <TableCell className="px-2 text-right tabular-nums">{formatCompact(ep.totalDeliveries)}</TableCell>
                      <TableCell className={cn('px-2 text-right tabular-nums', STATUS_TEXT[rateKind])}>{formatRate(ep.successRate)}%</TableCell>
                      <TableCell className="px-2 text-right font-mono text-[12px] text-muted-foreground">{formatMs(ep.avgLatencyMs)}</TableCell>
                      <TableCell className="px-2 text-right font-mono text-[12px] text-muted-foreground">{formatMs(ep.p95LatencyMs)}</TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </section>
      </div>
    </div>
  );
}
