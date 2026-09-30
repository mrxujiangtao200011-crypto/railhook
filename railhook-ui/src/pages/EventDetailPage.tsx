import { useMemo, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { Copy, Send, Share2, Terminal, FileJson, Loader2, ExternalLink } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { useEndpoints, useEvent, useEventTypes } from '../api/queries';
import { deliveriesApi } from '../api/deliveries.api';
import { debugLinksApi } from '../api/debugLinks.api';
import { useQuery } from '@tanstack/react-query';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import { formatBytes, sendEventCurl } from '../lib/publicSnippets';
import { showSuccess, showApiError } from '../lib/toast';
import PageSkeleton, { SkeletonTable } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import StatusBadge, { kindOfDeliveryStatus } from '../components/StatusBadge';
import { Button } from '../components/ui/button';
import { usePermissions } from '../auth/usePermissions';
import type { DeliveryResponse, PageResponse } from '../types/api.types';
import { railFromCounts } from './attemptRailData';
import { AttemptCell } from './tableParts';
import { cn } from '../lib/utils';
import { JsonView, Ledger, LedgerRow, Section } from '../components/port/p1/kit';

function hostOf(url: string) {
  try {
    const u = new URL(url);
    return u.host + (u.pathname === '/' ? '' : u.pathname);
  } catch {
    return url;
  }
}

export default function EventDetailPage() {
  const { t } = useTranslation();
  const { projectId, eventId } = useParams<{ projectId: string; eventId: string }>();
  const navigate = useNavigate();
  const { canManageEndpoints } = usePermissions();
  const [sharingDebug, setSharingDebug] = useState(false);

  const {
    data: event, isLoading, isError, error, refetch, isRefetching,
  } = useEvent(projectId, eventId);
  const { data: eventTypes } = useEventTypes(projectId);
  const { data: endpoints } = useEndpoints(projectId);
  const endpointUrl = useMemo(() => new Map((endpoints ?? []).map((e) => [e.id, e.url])), [endpoints]);

  const { data: deliveriesData, isLoading: deliveriesLoading, refetch: refetchDeliveries } = useQuery({
    // Prefixed with 'deliveries' so a replay's invalidation reaches this list too.
    queryKey: ['deliveries', projectId, eventId, 'event-detail'],
    queryFn: () => deliveriesApi.listByProject(projectId!, { eventId, size: 50 }),
    enabled: !!projectId && !!eventId,
  });

  const { data: debugLinks = [], refetch: refetchLinks } = useQuery({
    queryKey: ['debug-links', projectId, eventId],
    queryFn: () => debugLinksApi.listForEvent(projectId!, eventId!),
    enabled: !!projectId && !!eventId,
  });

  const deliveries: DeliveryResponse[] = (deliveriesData as PageResponse<DeliveryResponse>)?.content ?? [];

  const handleCopy = (text: string, copiedMessage: string) => {
    navigator.clipboard.writeText(text);
    showSuccess(copiedMessage);
  };

  const handleShareDebug = async () => {
    if (!projectId || !eventId) return;
    setSharingDebug(true);
    try {
      const link = await debugLinksApi.create(projectId, eventId, { expiryHours: 24 });
      await navigator.clipboard.writeText(link.shareUrl);
      showSuccess(t('eventDetail.debugLinkCreated'));
      refetchLinks();
    } catch (err: any) {
      showApiError(err, 'eventDetail.debugLinkFailed');
    } finally {
      setSharingDebug(false);
    }
  };

  const handleReplayUndelivered = async () => {
    const undelivered = deliveries.filter(d => d.status === 'FAILED' || d.status === 'DLQ');
    for (const d of undelivered) {
      try { await deliveriesApi.replay(d.id); } catch { /* keep going: one failure must not stop the rest */ }
    }
    showSuccess(t('eventDetail.replayedFailed', { count: undelivered.length }));
    refetchDeliveries();
  };

  const matchingSchema = eventTypes?.find((et) => et.name === event?.eventType);

  if (isLoading) return <PageSkeleton maxWidth="max-w-none" />;
  if (isError) {
    return (
      <div className="p-4 lg:p-8">
        <ErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      </div>
    );
  }
  if (!event) {
    return (
      <div className="p-4 lg:p-8">
        <EmptyState icon={FileJson} title={t('events.details.notFound')} />
      </div>
    );
  }

  const undeliveredCount = deliveries.filter(d => d.status === 'FAILED' || d.status === 'DLQ').length;

  return (
    <div className="p-4 lg:p-8">
      <PageHeader
        title={event.eventType}
        description={(
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="break-all font-mono text-[12px] text-foreground">{event.id}</span>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => handleCopy(event.id, t('eventDetail.eventIdCopied'))}
              title={t('common.copyId')}
              aria-label={t('common.copyId')}
            >
              <Copy className="h-3.5 w-3.5" />
            </Button>
            <span title={formatDateTime(event.createdAt)}>{'\u00b7 '}{formatRelativeTime(event.createdAt)}</span>
          </span>
        )}
        actions={
          <>
            <Button variant="outline" onClick={() => handleCopy(sendEventCurl({ payload: event.payload || '{}' }), t('eventDetail.curlCopied'))}>
              <Terminal className="h-4 w-4" /> {t('eventDetail.copyCurl')}
            </Button>
            <Button variant="outline" onClick={handleShareDebug} disabled={sharingDebug}>
              {sharingDebug ? <Loader2 className="h-4 w-4 animate-spin" /> : <Share2 className="h-4 w-4" />}
              {t('eventDetail.share')}
            </Button>
            {undeliveredCount > 0 && canManageEndpoints && (
              <Button onClick={handleReplayUndelivered}>
                <Send className="h-4 w-4" /> {t('eventDetail.replayFailed', { count: undeliveredCount })}
              </Button>
            )}
          </>
        }
      />

      <div className="grid gap-x-10 gap-y-10 xl:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
        <div className="min-w-0 space-y-3">
          {event.payload ? (
            <JsonView value={event.payload} title={t('eventDetail.rawPayload')} maxHeight="max-h-[70vh]" />
          ) : (
            <p className="border-t border-rail py-6 text-[13px] italic text-muted-foreground">{t('events.details.noPayload')}</p>
          )}
          <p className="text-[12px] text-muted-foreground">
            {t('eventDetail.sanitizedHint')} {t('eventDetail.sanitizedUseDebug')}
          </p>
        </div>

        <div className="min-w-0 space-y-10">
          <Ledger>
            <LedgerRow label={t('eventDetail.eventType')}><span className="font-mono text-[12px]">{event.eventType}</span></LedgerRow>
            <LedgerRow label={t('eventDetail.deliveriesCount')}>{event.deliveriesCreated ?? deliveries.length}</LedgerRow>
            <LedgerRow label={t('eventDetail.payloadSize')}>
              {event.payload ? formatBytes(new TextEncoder().encode(event.payload).length) : '—'}
            </LedgerRow>
            <LedgerRow label={t('events.created')}><span className="font-mono text-[12px]">{formatDateTime(event.createdAt)}</span></LedgerRow>
          </Ledger>

          <Section title={t('eventDetail.tabs.deliveries')}>
            {deliveriesLoading ? (
              <SkeletonTable rows={3} />
            ) : deliveries.length === 0 ? (
              <EmptyState
                icon={Send}
                title={t('events.details.noDeliveries')}
                description={(
                  <Trans i18nKey="events.details.noDeliveriesNoSub" values={{ eventType: event.eventType }} components={{ strong: <strong /> }} />
                )}
                action={(
                  <Button variant="outline" size="sm" onClick={() => navigate(`/admin/projects/${projectId}/subscriptions`)}>
                    {t('deliveries.noDeliveriesForEventAction')}
                  </Button>
                )}
                className="py-6"
              />
            ) : (
              <ul className="border-t border-rail">
                {deliveries.map((d) => {
                  const rail = railFromCounts(d.attemptCount, d.maxAttempts, d.status);
                  const url = endpointUrl.get(d.endpointId);
                  const failed = d.status === 'FAILED' || d.status === 'DLQ';
                  return (
                    <li key={d.id} className={cn('border-b border-rail py-3', failed && 'bg-halt-soft/40')}>
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0 flex-1">
                          <Link
                            to={`/admin/projects/${projectId}/deliveries?eventId=${event.id}`}
                            className="block truncate font-mono text-[12px] underline-offset-4 hover:underline"
                            title={url ?? d.endpointId}
                          >
                            {url ? hostOf(url) : d.endpointId.substring(0, 8)}
                          </Link>
                          <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1">
                            <StatusBadge kind={kindOfDeliveryStatus(d.status)} label={t(`deliveries.status.${d.status}`)} />
                            <AttemptCell
                              rail={rail.attempts}
                              maxAttempts={rail.maxAttempts}
                              attemptCount={d.attemptCount}
                              ladderLength={d.maxAttempts}
                              nextRetryAt={d.status === 'PENDING' ? d.nextRetryAt : undefined}
                            />
                          </div>
                        </div>
                        {failed && canManageEndpoints && (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            onClick={() => deliveriesApi.replay(d.id).then(() => { showSuccess(t('eventDetail.replayed')); refetchDeliveries(); })}
                            title={t('events.details.replay')}
                            aria-label={t('events.details.replay')}
                          >
                            <Send className="h-3.5 w-3.5" />
                          </Button>
                        )}
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </Section>

          <Section title={t('eventDetail.schemaInfo')}>
            {matchingSchema ? (
              <div className="space-y-2 border-t border-rail pt-3">
                <p className="font-mono text-[13px]">
                  {matchingSchema.name}
                  {matchingSchema.latestVersion && <span className="ml-2 text-muted-foreground">v{matchingSchema.latestVersion}</span>}
                </p>
                {matchingSchema.description && <p className="text-[13px] text-muted-foreground">{matchingSchema.description}</p>}
                <Button variant="outline" size="sm" onClick={() => navigate(`/admin/projects/${projectId}/schemas`)}>
                  <ExternalLink className="h-3.5 w-3.5" /> {t('eventDetail.viewSchemaRegistry')}
                </Button>
              </div>
            ) : (
              <p className="border-t border-rail pt-3 text-[13px] text-muted-foreground">{t('eventDetail.noSchema', { type: event.eventType })}</p>
            )}
          </Section>

          <Section
            title={t('eventDetail.tabs.debug')}
            aside={(
              <Button size="sm" variant="outline" onClick={handleShareDebug} disabled={sharingDebug}>
                {sharingDebug ? <Loader2 className="h-4 w-4 animate-spin" /> : <Share2 className="h-4 w-4" />}
                {t('eventDetail.createLink')}
              </Button>
            )}
          >
            {debugLinks.length === 0 ? (
              <p className="border-t border-rail pt-3 text-[13px] text-muted-foreground">{t('eventDetail.noDebugLinks')}</p>
            ) : (
              <ul className="border-t border-rail">
                {debugLinks.map((link) => (
                  <li key={link.id} className="flex items-center justify-between gap-3 border-b border-rail py-2.5">
                    <div className="min-w-0 flex-1">
                      <a href={link.shareUrl} target="_blank" rel="noopener noreferrer" className="block truncate font-mono text-[12px] link-ink">
                        {link.shareUrl}
                      </a>
                      <p className="mt-0.5 text-[12px] text-muted-foreground">
                        {t('eventDetail.views', { count: link.viewCount })}{' \u00b7 '}{t('eventDetail.expires', { time: formatRelativeTime(link.expiresAt) })}
                      </p>
                    </div>
                    <Button variant="ghost" size="icon-sm" onClick={() => handleCopy(link.shareUrl, t('eventDetail.debugLinkCopied'))} title={t('eventDetail.copyLink')} aria-label={t('eventDetail.copyLink')}>
                      <Copy className="h-3.5 w-3.5" />
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </Section>
        </div>
      </div>
    </div>
  );
}
