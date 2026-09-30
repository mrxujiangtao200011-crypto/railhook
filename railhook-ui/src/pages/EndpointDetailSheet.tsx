import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import type { EndpointPerformance } from '../api/dashboard.api';
import type { EndpointResponse } from '../types/api.types';
import { useDeliveries, useSubscriptions } from '../api/queries';
import type { DeliveryFilters } from '../api/deliveries.api';
import StatusBadge, { EnabledBadge, kindOfDeliveryStatus } from '../components/StatusBadge';
import AttemptRail from '../components/AttemptRail';
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '../components/ui/sheet';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import { cn } from '../lib/utils';
import { railFromCounts } from './attemptRailData';

function Fact({ label, children, mono = false }: { label: string; children: React.ReactNode; mono?: boolean }) {
  return (
    <div className="grid grid-cols-[9rem_minmax(0,1fr)] gap-4 border-b border-rail py-2.5 text-sm max-sm:grid-cols-1 max-sm:gap-0.5">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={cn('min-w-0 break-words', mono && 'font-mono text-[12px] leading-5')}>{children}</dd>
    </div>
  );
}

export default function EndpointDetailSheet({
  projectId, endpoint, performance, onClose,
}: {
  projectId: string | undefined;
  endpoint: EndpointResponse | null;
  performance?: EndpointPerformance;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const filters = useMemo<DeliveryFilters>(
    () => ({ endpointId: endpoint?.id, page: 0, size: 10, sort: 'createdAt,desc' }),
    [endpoint?.id],
  );
  const { data: recent } = useDeliveries(endpoint ? projectId : undefined, filters);
  const { data: subscriptions = [] } = useSubscriptions(endpoint ? projectId : undefined);
  const mine = subscriptions.filter((s) => s.endpointId === endpoint?.id);

  return (
    <Sheet open={endpoint !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-xl">
        {endpoint && (
          <>
            <SheetHeader>
              <SheetTitle className="break-all pr-8 font-mono text-[15px]">{endpoint.url.replace(/^https?:\/\//, '')}</SheetTitle>
              <SheetDescription asChild>
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <EnabledBadge enabled={endpoint.enabled} autoDisabled={Boolean(endpoint.autoDisabledAt)} />
                  {endpoint.description && <span className="text-[13px]">{endpoint.description}</span>}
                </div>
              </SheetDescription>
            </SheetHeader>

            {(endpoint.autoDisabledReason || (endpoint.consecutiveFailures ?? 0) > 0) && (
              <div className="mt-5 border-l-2 border-halt py-1 pl-3 text-sm">
                {endpoint.autoDisabledReason && <p className="text-halt">{endpoint.autoDisabledReason}</p>}
                {(endpoint.consecutiveFailures ?? 0) > 0 && (
                  <p className="text-muted-foreground">
                    {t('endpoints.detail.failingInARow', {
                      count: endpoint.consecutiveFailures,
                      since: endpoint.failingSince ? formatRelativeTime(endpoint.failingSince) : '—',
                    })}
                  </p>
                )}
              </div>
            )}

            <section className="mt-6">
              <h3 className="mb-2 text-[13px] text-muted-foreground">{t('endpoints.detail.health')}</h3>
              {performance && performance.totalDeliveries > 0 ? (
                <dl className="border-t border-rail">
                  <Fact label={t('endpoints.detail.delivered')}>
                    <span className={cn(performance.status === 'FAILING' && 'text-halt', performance.status === 'DEGRADED' && 'text-retry')}>
                      {performance.successRate.toFixed(1)}%
                    </span>
                    <span className="ml-2 text-muted-foreground">
                      {t('endpoints.detail.ofDeliveries', { delivered: performance.successfulDeliveries, total: performance.totalDeliveries })}
                    </span>
                  </Fact>
                  <Fact label={t('endpoints.detail.p95')} mono>{Math.round(performance.p95LatencyMs)} ms</Fact>
                  {performance.lastDeliveryAt && (
                    <Fact label={t('endpoints.detail.lastDelivery')}>{formatRelativeTime(performance.lastDeliveryAt)}</Fact>
                  )}
                </dl>
              ) : (
                <p className="border-t border-rail py-3 text-[13px] text-muted-foreground">{t('endpoints.detail.noTraffic')}</p>
              )}
            </section>

            <section className="mt-8">
              <div className="mb-2 flex items-baseline justify-between gap-3">
                <h3 className="text-[13px] text-muted-foreground">{t('endpoints.detail.recent')}</h3>
                <Link to={`/admin/projects/${projectId}/deliveries`} onClick={onClose} className="text-[13px] underline decoration-rail underline-offset-4 hover:decoration-foreground">
                  {t('common.viewAll')}
                </Link>
              </div>
              {(recent?.content ?? []).length === 0 ? (
                <p className="border-t border-rail py-3 text-[13px] text-muted-foreground">{t('endpoints.detail.noRecent')}</p>
              ) : (
                <ul className="border-t border-rail">
                  {recent!.content.map((delivery) => {
                    const rail = railFromCounts(delivery.attemptCount, delivery.maxAttempts, delivery.status);
                    return (
                      <li key={delivery.id} className="flex items-center gap-3 border-b border-rail py-2 text-[13px]">
                        <StatusBadge kind={kindOfDeliveryStatus(delivery.status)} label={t(`deliveries.status.${delivery.status}`)} />
                        <span className="min-w-0 flex-1 truncate font-mono text-[12px]">{delivery.eventType ?? delivery.eventId}</span>
                        <AttemptRail
                          attempts={rail.attempts}
                          maxAttempts={rail.maxAttempts}
                          ariaLabel={t('deliveries.rail.label', { count: delivery.attemptCount, total: delivery.maxAttempts })}
                        />
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>

            <section className="mt-8">
              <h3 className="mb-2 text-[13px] text-muted-foreground">{t('endpoints.detail.subscriptions')}</h3>
              {mine.length === 0 ? (
                <p className="border-t border-rail py-3 text-[13px] text-muted-foreground">{t('endpoints.detail.noSubscriptions')}</p>
              ) : (
                <ul className="border-t border-rail">
                  {mine.map((s) => (
                    <li key={s.id} className="flex items-center justify-between gap-3 border-b border-rail py-2 text-[13px]">
                      <span className={cn('min-w-0 truncate font-mono text-[12px]', !s.enabled && 'text-muted-foreground line-through')}>{s.eventType}</span>
                      {s.transformationName && <span className="truncate text-[12px] text-muted-foreground">{s.transformationName}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <section className="mt-8">
              <dl className="border-t border-rail">
                <Fact label={t('endpoints.url')} mono>{endpoint.url}</Fact>
                <Fact label={t('endpoints.verification')}>{t(`endpoints.${(endpoint.verificationStatus ?? 'PENDING').toLowerCase()}`)}</Fact>
                <Fact label={t('subscriptions.created')} mono>{formatDateTime(endpoint.createdAt)}</Fact>
                <Fact label="ID" mono>{endpoint.id}</Fact>
              </dl>
            </section>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
