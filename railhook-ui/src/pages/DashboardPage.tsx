import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { BarChart3, Radio, Webhook } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import {
  useAnalytics, useDashboardStats, useDeliveries, useOpenIncidentCount,
  useProjects, useUnresolvedAlertCount,
} from '../api/queries';
import { projectToOpen, rememberProject } from '../lib/lastProject';
import type { DeliveryFilters } from '../api/deliveries.api';
import { formatDateTime, formatDateTimeShort, formatRelativeTime, formatTime } from '../lib/date';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import AttemptRail from '../components/AttemptRail';
import { railFromCounts } from './attemptRailData';
import GettingStarted from '../components/GettingStarted';
import FirstProjectCard from '../components/FirstProjectCard';
import { cn } from '../lib/utils';
import { Select } from '../components/ui/select';
import { Button } from '../components/ui/button';
import {
  ChartCard, OutcomeChart, STATUS_FILL, STATUS_TEXT, coerceDeliveryStats,
  formatCompact, formatRate, kindOfSuccessRate, outcomeLegend, verdictOfDeliveryStats,
} from '../components/charts';
import type { StatusKind } from '../components/StatusBadge';

/** Stable so the deliveries query key does not change on every render. */
const IN_FLIGHT_FILTER: DeliveryFilters = { status: 'PENDING', page: 0, size: 4, sort: 'createdAt,desc' };

const DASHBOARD_PERIOD = '7d' as const;

function SkeletonDashboard() {
  return (
    <PageSkeleton maxWidth="max-w-none">
      <SkeletonCards count={2} height="h-[240px]" cols="xl:grid-cols-[minmax(0,1fr)_20rem]" />
    </PageSkeleton>
  );
}

function LedgerRow({ label, to, children }: { label: string; to?: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-rail py-2.5 text-sm">
      <dt className="flex-shrink-0 text-muted-foreground">
        {to ? <Link to={to} className="underline decoration-transparent underline-offset-4 hover:text-foreground hover:decoration-foreground">{label}</Link> : label}
      </dt>
      <dd className="min-w-0 text-right tabular-nums">{children}</dd>
    </div>
  );
}

function formatLatency(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(ms >= 10_000 ? 0 : 1)} s` : `${Math.round(ms)} ms`;
}

interface AttentionItem { key: string; kind: StatusKind; label: string; to: string }

export default function DashboardPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();

  const {
    data: projects = [], isLoading: projectsLoading, isError: projectsIsError,
    error: projectsError, refetch: refetchProjects,
  } = useProjects();
  const [chosenProjectId, setChosenProjectId] = useState('');
  // The project you were last in, not the account's first: Overview has no project in its URL.
  const selectedProjectId = chosenProjectId || (projectToOpen(projects) ?? '');

  const selectProject = (id: string) => {
    setChosenProjectId(id);
    rememberProject(id);
  };

  const projectId = selectedProjectId || undefined;

  const {
    data: dashboardStats, isLoading: statsLoading, isError: statsIsError,
    error: statsError, refetch: refetchStats,
  } = useDashboardStats(projectId);

  const {
    data: analytics, isLoading: analyticsLoading, isError: analyticsIsError,
    error: analyticsError, isFetching: analyticsFetching, refetch: refetchAnalytics,
  } = useAnalytics(projectId, { period: DASHBOARD_PERIOD });

  const { data: inFlightPage } = useDeliveries(projectId, IN_FLIGHT_FILTER);
  const { data: unresolvedAlerts } = useUnresolvedAlertCount(projectId);
  const { data: openIncidents } = useOpenIncidentCount(projectId);

  const selectedProject = projects.find((p) => p.id === selectedProjectId);

  // A card's own failure is reported in the card, not by blanking the dashboard.
  const pageIsError = projectsIsError || statsIsError;
  const retryPage = () => { refetchProjects(); refetchStats(); };

  // Coerced: the first screen a new account sees must render before the data does.
  const stats = coerceDeliveryStats(dashboardStats?.deliveryStats);
  const recentEvents = dashboardStats?.recentEvents ?? [];
  const endpointHealth = dashboardStats?.endpointHealth ?? [];

  const verdict = verdictOfDeliveryStats(stats);
  const series = useMemo(
    () => (analytics?.deliveryTimeSeries ?? []).map((p) => ({
      timestamp: p.timestamp,
      success: p.success ?? 0,
      failed: p.failed ?? 0,
    })),
    [analytics]
  );

  const outcomeLabels = {
    success: t('dashboard.outcome.delivered'),
    failed: t('dashboard.outcome.failed'),
  };

  const alertCount = unresolvedAlerts?.count ?? 0;
  const incidentCount = openIncidents?.count ?? 0;

  const inFlight = inFlightPage?.content ?? [];
  const [showAll, setShowAll] = useState(false);

  const base = `/admin/projects/${selectedProjectId}`;
  const attention: AttentionItem[] = [];
  if (stats.dlqDeliveries > 0) attention.push({ key: 'dlq', kind: 'halt', label: t('dashboard.attention.dlqItem', { count: stats.dlqDeliveries }), to: `${base}/dlq` });
  if (incidentCount > 0) attention.push({ key: 'incidents', kind: 'halt', label: t('dashboard.attention.incidentsItem', { count: incidentCount }), to: `${base}/incidents` });
  for (const endpoint of endpointHealth.filter((e) => e.enabled && e.totalDeliveries >= 10 && e.successRate < 90).slice(0, 2)) {
    attention.push({
      key: endpoint.id,
      kind: 'halt',
      label: t('dashboard.attention.endpointItem', { url: endpoint.url.replace(/^https?:\/\//, ''), rate: formatRate(endpoint.successRate) }),
      to: `${base}/endpoints`,
    });
  }
  if (stats.failedDeliveries > 0) attention.push({ key: 'failed', kind: 'retry', label: t('dashboard.attention.failedItem', { count: stats.failedDeliveries }), to: `${base}/deliveries?status=FAILED` });
  if (alertCount > 0) attention.push({ key: 'alerts', kind: 'retry', label: t('dashboard.attention.alertsItem', { count: alertCount }), to: `${base}/alerts` });

  if (projectsLoading) return <SkeletonDashboard />;

  if (pageIsError) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState
          error={projectsError ?? statsError}
          fallbackKey="dashboard.toast.loadFailed"
          onRetry={retryPage}
        />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-8">
      <PageHeader
        eyebrow={selectedProject?.name}
        title={t('dashboard.headline')}
        description={t('dashboard.headlineDesc')}
        actions={
          <>
            {projects.length > 1 && (
              <div className="w-48">
                <Select
                  aria-label={t('dashboard.projectPicker')}
                  value={selectedProjectId}
                  onChange={(e) => selectProject(e.target.value)}
                >
                  {projects.map((project) => (
                    <option key={project.id} value={project.id}>{project.name}</option>
                  ))}
                </Select>
              </div>
            )}
            {selectedProjectId && (
              <Button variant="outline" size="sm" onClick={() => navigate(`/admin/projects/${selectedProjectId}/analytics`)}>
                <BarChart3 className="h-4 w-4" /> {t('dashboard.openAnalytics')}
              </Button>
            )}
          </>
        }
      />

      {selectedProject && <GettingStarted projectId={projectId} />}

      {!selectedProject ? (
        <FirstProjectCard />
      ) : (
        <div className="animate-fade-in">
          <div className="grid gap-x-16 gap-y-10 xl:grid-cols-[minmax(0,1fr)_20rem]">
            <div className="min-w-0 space-y-10">
              {statsLoading ? (
                <div className="space-y-3" aria-hidden>
                  <div className="h-6 w-64 animate-pulse bg-muted" />
                  <div className="h-4 w-96 max-w-full animate-pulse bg-muted" />
                </div>
              ) : attention.length === 0 ? (
                <section>
                  <p className="flex items-center gap-2.5 text-[15px]">
                    <span aria-hidden className={cn('h-2 w-2 rounded-full', STATUS_FILL[verdict])} />
                    {t(`dashboard.verdict.${verdict}`)}
                  </p>
                  <p className="mt-1 pl-[18px] text-[13px] text-muted-foreground">
                    {stats.totalDeliveries > 0
                      ? t('dashboard.verdict.detail', { delivered: formatCompact(stats.successfulDeliveries), total: formatCompact(stats.totalDeliveries) })
                      : t('dashboard.verdict.idleDetail')}
                  </p>
                </section>
              ) : (
                <section aria-labelledby="dashboard-attention">
                  <h3 id="dashboard-attention" className="text-[22px] font-normal leading-tight tracking-[-0.015em]">
                    {t('dashboard.attention.count', { count: attention.length })}
                  </h3>
                  <ul className="mt-4 border-t border-rail">
                    {(showAll ? attention : attention.slice(0, 3)).map((item) => (
                      <li key={item.key} className="flex items-start gap-3 border-b border-rail py-3">
                        <span aria-hidden className={cn('mt-[7px] h-2 w-2 flex-shrink-0 rounded-full', STATUS_FILL[item.kind])} />
                        <p className="min-w-0 flex-1 break-words text-[15px] leading-snug">{item.label}</p>
                        <Link to={item.to} className="-my-1.5 flex min-h-[44px] flex-shrink-0 items-center px-1 text-[13px] underline decoration-rail underline-offset-4 hover:decoration-foreground">
                          {t('dashboard.attention.review')}
                        </Link>
                      </li>
                    ))}
                  </ul>
                  {attention.length > 3 && (
                    <button type="button" onClick={() => setShowAll((v) => !v)} className="mt-1 min-h-[44px] text-[13px] text-muted-foreground hover:text-foreground">
                      {showAll ? t('dashboard.attention.showLess') : t('dashboard.attention.showMore', { count: attention.length - 3 })}
                    </button>
                  )}
                </section>
              )}

              <ChartCard
                title={t('dashboard.outcome.title')}
                description={t('dashboard.outcome.desc')}
                legend={outcomeLegend(outcomeLabels)}
                bodyClass="h-[240px]"
                isLoading={analyticsLoading}
                error={analyticsIsError ? analyticsError : undefined}
                onRetry={() => refetchAnalytics()}
                isRefetching={analyticsFetching && !analyticsLoading}
                isEmpty={series.length === 0}
                emptyLabel={t('dashboard.outcome.empty')}
              >
                <OutcomeChart
                  data={series}
                  labels={outcomeLabels}
                  formatTick={formatTime}
                  formatStamp={formatDateTimeShort}
                />
              </ChartCard>
            </div>

            <div className="min-w-0 space-y-10 max-xl:max-w-xl">
              <section aria-label={t('dashboard.stats.window')}>
                <h3 className="mb-2 text-[13px] text-muted-foreground">{t('dashboard.stats.window')}</h3>
                <dl className="border-t border-rail">
                  <LedgerRow label={t('dashboard.verdict.label')}>
                    <span data-testid="delivery-health-figure" className={cn(stats.totalDeliveries > 0 && STATUS_TEXT[verdict])}>
                      {stats.totalDeliveries > 0 ? `${formatRate(stats.successRate)}%` : '—'}
                    </span>
                  </LedgerRow>
                  <LedgerRow label={t('dashboard.stats.deliveries')} to={`/admin/projects/${selectedProjectId}/deliveries`}>
                    {formatCompact(stats.totalDeliveries)}
                  </LedgerRow>
                  <LedgerRow label={t('dashboard.stats.inFlight')} to={`/admin/projects/${selectedProjectId}/deliveries?status=PENDING`}>
                    {formatCompact(stats.pendingDeliveries)}
                  </LedgerRow>
                  <LedgerRow label={t('dashboard.attention.failed')} to={`/admin/projects/${selectedProjectId}/deliveries?status=FAILED`}>
                    <span className={cn(stats.failedDeliveries > 0 && 'text-retry')}>{formatCompact(stats.failedDeliveries)}</span>
                  </LedgerRow>
                  <LedgerRow label={t('dashboard.stats.dlq')} to={`/admin/projects/${selectedProjectId}/dlq`}>
                    <span className={cn(stats.dlqDeliveries > 0 && 'text-halt')}>{formatCompact(stats.dlqDeliveries)}</span>
                  </LedgerRow>
                  {analytics?.overview && analytics.overview.totalDeliveries > 0 && (
                    <LedgerRow label={t('dashboard.stats.latencyP95')}>
                      <span className="font-mono text-[13px]">{formatLatency(analytics.overview.p95LatencyMs)}</span>
                    </LedgerRow>
                  )}
                </dl>
              </section>

              <section>
                <div className="mb-2 flex items-baseline justify-between gap-3">
                  <h3 className="text-[13px] text-muted-foreground">{t('dashboard.inFlight.title')}</h3>
                  {inFlight.length > 0 && (
                    <Link to={`/admin/projects/${selectedProjectId}/deliveries?status=PENDING`} className="text-[13px] underline decoration-rail underline-offset-4 hover:decoration-foreground">
                      {t('common.viewAll')}
                    </Link>
                  )}
                </div>
                {inFlight.length === 0 ? (
                  <p className="border-t border-rail py-4 text-[13px] text-muted-foreground">{t('dashboard.inFlight.empty')}</p>
                ) : (
                  <ul className="border-t border-rail">
                    {inFlight.map((delivery) => {
                      const rail = railFromCounts(delivery.attemptCount, delivery.maxAttempts, delivery.status);
                      return (
                        <li key={delivery.id} className="flex items-center justify-between gap-3 border-b border-rail py-2.5">
                          <span className="min-w-0">
                            <span className="block truncate font-mono text-[12px]">{delivery.eventType ?? delivery.id}</span>
                            <span className="block text-[12px] text-muted-foreground">{formatRelativeTime(delivery.createdAt)}</span>
                          </span>
                          <AttemptRail
                            attempts={rail.attempts}
                            maxAttempts={rail.maxAttempts}
                            ariaLabel={t('dashboard.inFlight.rail', { count: delivery.attemptCount, total: delivery.maxAttempts })}
                          />
                        </li>
                      );
                    })}
                  </ul>
                )}
              </section>
            </div>
          </div>

          <div className="mt-14 grid gap-x-16 gap-y-12 lg:grid-cols-2">
            <section className="min-w-0">
              <div className="mb-3 flex items-baseline justify-between gap-3">
                <h3 className="text-[15px] font-medium">{t('dashboard.endpointHealth.title')}</h3>
                <Link to={`/admin/projects/${selectedProjectId}/endpoints`} className="text-[13px] underline decoration-rail underline-offset-4 hover:decoration-foreground">{t('common.viewAll')}</Link>
              </div>
              {statsLoading ? (
                <SkeletonCards count={3} height="h-12" cols="grid-cols-1" />
              ) : endpointHealth.length === 0 ? (
                <EmptyState icon={Webhook} title={t('dashboard.endpointHealth.empty')} description={t('dashboard.endpointHealth.emptyDesc')} />
              ) : (
                <ul className="border-t border-rail">
                  {[...endpointHealth].sort((a, b) => b.totalDeliveries - a.totalDeliveries).slice(0, 5).map((endpoint) => {
                    const kind = kindOfSuccessRate(endpoint.successRate, endpoint.enabled);
                    return (
                      <li key={endpoint.id} className="border-b border-rail">
                        <Link to={`/admin/projects/${selectedProjectId}/endpoints`} className="block py-3 hover:bg-secondary/40">
                          <span className="flex items-baseline justify-between gap-3">
                            <span className="min-w-0 truncate font-mono text-[12px]" title={endpoint.url}>{endpoint.url.replace(/^https?:\/\//, '')}</span>
                            <span className={cn('flex-shrink-0 text-[13px] tabular-nums', STATUS_TEXT[kind])}>{formatRate(endpoint.successRate)}%</span>
                          </span>
                          <span className="mt-2 flex items-center gap-3">
                            <span className="relative h-[3px] flex-1 overflow-hidden bg-secondary">
                              <span className={cn('absolute inset-y-0 left-0', STATUS_FILL[kind])} style={{ width: `${Math.min(Math.max(endpoint.successRate, 0), 100)}%` }} />
                            </span>
                            <span className="flex-shrink-0 text-[12px] tabular-nums text-muted-foreground">
                              {t('dashboard.recentEvents.deliveryCount', { count: endpoint.totalDeliveries })}
                            </span>
                          </span>
                        </Link>
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>

            <section className="min-w-0">
              <div className="mb-3 flex items-baseline justify-between gap-3">
                <h3 className="text-[15px] font-medium">{t('dashboard.recentEvents.title')}</h3>
                <Link to={`/admin/projects/${selectedProjectId}/events`} className="text-[13px] underline decoration-rail underline-offset-4 hover:decoration-foreground">{t('common.viewAll')}</Link>
              </div>
              {statsLoading ? (
                <SkeletonCards count={3} height="h-12" cols="grid-cols-1" />
              ) : recentEvents.length === 0 ? (
                <EmptyState icon={Radio} title={t('dashboard.recentEvents.empty')} description={t('dashboard.recentEvents.emptyDesc')} />
              ) : (
                <ul className="border-t border-rail">
                  {recentEvents.slice(0, 6).map((event) => (
                    <li key={event.id} className="border-b border-rail">
                      <Link to={`/admin/projects/${selectedProjectId}/events/${event.id}`} className="flex min-h-[44px] items-center gap-3 py-2 hover:bg-secondary/40">
                        <span className="min-w-0 flex-1 truncate font-mono text-[12px]">{event.type}</span>
                        <span className="text-[12px] text-muted-foreground max-sm:hidden">{t('dashboard.recentEvents.deliveryCount', { count: event.deliveryCount })}</span>
                        <span className="w-24 text-right text-[12px] text-muted-foreground" title={formatDateTime(event.createdAt)}>{formatRelativeTime(event.createdAt)}</span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        </div>
      )}
    </div>
  );
}
