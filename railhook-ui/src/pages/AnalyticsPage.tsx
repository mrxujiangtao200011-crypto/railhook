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
import { Card } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { cn } from '../lib/utils';
import {
  BarRankChart, ChartCard, OutcomeChart, STATUS_TEXT, StatTile, TrendChart,
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

  const picker = (
    <div className="mb-4 space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <div
          role="group"
          aria-label={t('analytics.periodLabel')}
          className="inline-flex border border-rail bg-card p-0.5"
        >
          {PRESETS.map((p) => {
            const active = !customActive && 'period' in range && range.period === p;
            return (
              <button
                key={p}
                type="button"
                onClick={() => { setEditingCustom(false); setRange({ period: p }); }}
                aria-pressed={active}
                className={cn(
                  'px-3 py-1.5 font-mono text-xs transition-colors',
                  active ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'
                )}
              >
                {t(`analytics.periods.${p}`)}
              </button>
            );
          })}
          <button
            type="button"
            onClick={() => setEditingCustom(true)}
            aria-pressed={customActive}
            className={cn(
              'px-3 py-1.5 font-mono text-xs transition-colors',
              customActive
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:text-foreground'
            )}
          >
            {t('analytics.custom')}
          </button>
        </div>
        {editingCustom && (
          <>
            <Input
              type="datetime-local"
              aria-label={t('analytics.from')}
              value={draftFrom}
              onChange={(e) => setDraftFrom(e.target.value)}
              className="w-auto"
            />
            <Input
              type="datetime-local"
              aria-label={t('analytics.to')}
              value={draftTo}
              onChange={(e) => setDraftTo(e.target.value)}
              className="w-auto"
            />
            <Button size="sm" disabled={!draftRange} onClick={() => draftRange && setRange(draftRange)}>
              {t('analytics.apply')}
            </Button>
          </>
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
        <SkeletonCards count={4} height="h-[104px]" cols="grid-cols-2 lg:grid-cols-4" />
        <SkeletonCards count={2} height="h-[300px]" cols="lg:grid-cols-2" />
      </PageSkeleton>
    );
  }

  if (isError || !analytics) {
    return (
      <div className="p-4 lg:p-6">
        <PageHeader eyebrow={period} description={t('analytics.subtitle')} />
        {picker}
        <ErrorState error={error} fallbackKey="analytics.loadFailed" onRetry={() => refetch()} />
      </div>
    );
  }

  const hasDeliveries = overview.totalDeliveries > 0;

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={period}
        description={t('analytics.subtitle')}
        actions={actions}
      />

      {picker}

      <div className="space-y-4">
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <StatTile
            label={t('analytics.successRate')}
            value={hasDeliveries ? `${formatRate(overview.successRate)}%` : '—'}
            hint={t('analytics.tiles.successRateHint', {
              delivered: formatCompact(overview.successfulDeliveries),
              total: formatCompact(overview.totalDeliveries),
            })}
          />
          <StatTile
            label={t('analytics.avgLatency')}
            value={hasDeliveries ? formatMs(overview.avgLatencyMs) : '—'}
            hint={t('analytics.tiles.latencyHint', {
              p95: formatMs(overview.p95LatencyMs),
              p99: formatMs(overview.p99LatencyMs),
            })}
          />
          <StatTile
            label={t('analytics.throughput')}
            value={overview.deliveriesPerSecond.toFixed(2)}
            hint={t('analytics.deliveriesPerSec')}
          />
          <StatTile
            label={t('analytics.failed')}
            value={formatCompact(overview.failedDeliveries)}
            hint={t('analytics.tiles.failedHint', {
              percent: formatRate(share(overview.failedDeliveries, overview.totalDeliveries)),
            })}
            badge={overview.failedDeliveries > 0 ? <StatusBadge kind="retry" label={t('analytics.tiles.failedBadge')} icon={false} /> : undefined}
          />
        </div>

        <ChartCard
          title={t('analytics.outcome.title')}
          description={t('analytics.outcome.desc')}
          eyebrow={period}
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

        <div className="grid gap-4 lg:grid-cols-2">
          <ChartCard
            title={t('analytics.responseLatency')}
            description={t('analytics.responseLatencyDesc')}
            eyebrow={period}
            bodyClass="h-[260px]"
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

          <ChartCard
            title={t('analytics.eventTypes')}
            description={t('analytics.eventTypesDesc')}
            eyebrow={period}
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
        </div>

        <div className="grid gap-4 lg:grid-cols-3">
          <ChartCard
            title={t('analytics.latencyPercentiles')}
            description={t('analytics.latencyPercentilesDesc')}
            eyebrow={period}
            bodyClass="h-[166px]"
            isRefetching={isFetching}
            isEmpty={!hasDeliveries}
            emptyLabel={t('analytics.noLatencyPercentiles')}
          >
            <BarRankChart
              data={percentileRows}
              seriesLabel={t('analytics.latencySeries')}
              formatValue={formatMs}
              ordinal
              categoryWidth={44}
            />
          </ChartCard>

          <Card className="overflow-hidden lg:col-span-2">
            <div className="px-5 pb-3 pt-5">
              <div className="mono-label mb-1">{period}</div>
              <h3 className="text-sm font-medium leading-tight">{t('analytics.endpointPerformance')}</h3>
              <p className="mt-0.5 text-xs text-muted-foreground">{t('analytics.endpointPerformanceDesc')}</p>
            </div>
            {endpointPerformance.length === 0 ? (
              <p className="px-5 pb-8 pt-4 text-center text-sm text-muted-foreground">
                {t('analytics.noEndpointData')}
              </p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('analytics.epColumns.endpoint')}</TableHead>
                    <TableHead className="w-[120px]">{t('analytics.epColumns.status')}</TableHead>
                    <TableHead className="w-[110px] text-right">{t('analytics.epColumns.deliveries')}</TableHead>
                    <TableHead className="w-[100px] text-right">{t('analytics.epColumns.success')}</TableHead>
                    <TableHead className="w-[100px] text-right">{t('analytics.epColumns.latency')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {endpointPerformance.map((ep) => (
                    <TableRow key={ep.endpointId}>
                      <TableCell className="max-w-0">
                        <Link
                          to={`/admin/projects/${projectId}/deliveries?endpointId=${ep.endpointId}`}
                          className="block truncate font-mono text-xs hover:underline"
                        >
                          {ep.url}
                        </Link>
                      </TableCell>
                      <TableCell>
                        <StatusBadge
                          kind={kindOfEndpointStatus(ep.status)}
                          label={t(`analytics.endpointStatus.${ep.status}`)}
                          icon={false}
                        />
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums">
                        {formatCompact(ep.totalDeliveries)}
                      </TableCell>
                      <TableCell
                        className={cn(
                          'text-right font-mono text-xs tabular-nums',
                          STATUS_TEXT[kindOfSuccessRate(ep.successRate, ep.enabled)]
                        )}
                      >
                        {formatRate(ep.successRate)}%
                      </TableCell>
                      <TableCell className="text-right font-mono text-xs tabular-nums text-muted-foreground">
                        {formatMs(ep.avgLatencyMs)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}
