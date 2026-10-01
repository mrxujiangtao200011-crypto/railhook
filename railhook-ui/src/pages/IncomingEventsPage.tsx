import { useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowDownToLine, Clock, Loader2, RotateCcw, X } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import StatusBadge, { kindOfDeliveryStatus, type StatusKind } from '../components/StatusBadge';
import AttemptRail from '../components/AttemptRail';
import {
  useProject, useIncomingSources, useIncomingEvents, useIncomingEventAttempts, useReplayIncomingEvent,
  useBulkReplayIncomingEvents,
} from '../api/queries';
import type { IncomingEventResponse, IncomingForwardAttemptResponse } from '../types/api.types';
import { Button } from '../components/ui/button';
import { Select } from '../components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '../components/ui/sheet';
import { usePermissions } from '../auth/usePermissions';
import { railFromForwardAttempts } from './attemptRailData';
import { CopyId, FilterBar, FilterField, SearchField, SelectBox, SelectionBar } from './tableParts';
import Callout from '../components/Callout';
import { formatJson } from '../lib/json';
import { cn } from '../lib/utils';
import { JsonView, Tabs, useWide } from '../components/port/p1/kit';

function verificationOf(event: IncomingEventResponse): { kind: StatusKind; key: string } {
  if (event.verified === true) return { kind: 'ok', key: 'incomingEvents.verified' };
  if (event.verified === false) return { kind: 'halt', key: 'incomingEvents.failed' };
  return { kind: 'idle', key: 'incomingEvents.noVerification' };
}

type DetailTab = 'forwards' | 'body' | 'headers';

function EventDetail({
  event, projectId, canReplay, onReplay, onClose,
}: {
  event: IncomingEventResponse;
  projectId: string | undefined;
  canReplay: boolean;
  onReplay: () => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [tab, setTab] = useState<DetailTab>('forwards');
  const { data: attemptsPage, isLoading: loadingAttempts } = useIncomingEventAttempts(projectId, event.id);
  const attempts = useMemo(() => attemptsPage?.content ?? [], [attemptsPage]);
  const forwards = useMemo(() => {
    const byDestination = new Map<string, IncomingForwardAttemptResponse[]>();
    for (const attempt of attempts) {
      const list = byDestination.get(attempt.destinationId) ?? [];
      list.push(attempt);
      byDestination.set(attempt.destinationId, list);
    }
    return Array.from(byDestination.entries()).map(([destinationId, list]) => {
      const ordered = [...list].sort((a, b) => a.attemptNumber - b.attemptNumber);
      return { destinationId, attempts: ordered, latest: ordered[ordered.length - 1], rail: railFromForwardAttempts(ordered) };
    });
  }, [attempts]);
  const verification = verificationOf(event);

  return (
    <div className="flex h-full min-w-0 flex-col">
      <div className="flex items-start gap-3 border-b border-rail pb-4">
        <div className="min-w-0 flex-1">
          <p className="break-all font-mono text-[13px]"><span className="text-muted-foreground">{event.method}</span> {event.path}</p>
          <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[13px]">
            <StatusBadge kind={verification.kind} label={t(verification.key)} />
            <span className="font-mono text-[12px] text-muted-foreground">{event.requestId}</span>
          </div>
          {event.verificationError && <p className="mt-2 break-words font-mono text-[12px] text-halt">{event.verificationError}</p>}
          <dl className="mt-3 grid grid-cols-[7rem_minmax(0,1fr)] gap-x-3 gap-y-1 text-[12px]">
            <dt className="text-muted-foreground">{t('incomingEvents.columns.source')}</dt>
            <dd className="truncate">{event.sourceName || event.incomingSourceId}</dd>
            <dt className="text-muted-foreground">{t('incomingEvents.columns.received')}</dt>
            <dd className="font-mono">{formatDateTime(event.receivedAt)}</dd>
            <dt className="text-muted-foreground">{t('incomingEvents.columns.contentType')}</dt>
            <dd className="truncate font-mono">{event.contentType || '—'}</dd>
            <dt className="text-muted-foreground">{t('incomingEvents.columns.clientIp')}</dt>
            <dd className="truncate font-mono">{event.clientIp || '—'}</dd>
          </dl>
        </div>
        <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label={t('common.close')} title={t('common.close')} className="-mr-2 -mt-1 text-muted-foreground">
          <X className="h-4 w-4" />
        </Button>
      </div>

      <div className="mt-2">
        <Tabs
          label={t('incomingEvents.detail.title')}
          value={tab}
          onChange={setTab}
          items={[
            { value: 'forwards', label: t('incomingEvents.detail.forwards'), count: forwards.length },
            { value: 'body', label: t('incomingEvents.detail.body') },
            { value: 'headers', label: t('incomingEvents.detail.headers') },
          ]}
        />
      </div>

      <div role="tabpanel" className="min-h-0 flex-1 overflow-y-auto py-4">
        {tab === 'forwards' && (loadingAttempts ? (
          <SkeletonRows count={2} height="h-16" />
        ) : forwards.length === 0 ? (
          <p className="flex items-center gap-2 text-[13px] text-muted-foreground"><Clock className="h-4 w-4" aria-hidden />{t('incomingEvents.detail.noAttempts')}</p>
        ) : (
          <div className="space-y-8">
            {forwards.map((forward) => (
              <div key={forward.destinationId} className="space-y-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="min-w-0 truncate font-mono text-[12px]" title={forward.latest.destinationUrl}>
                    {forward.latest.destinationUrl || forward.destinationId}
                  </span>
                  <StatusBadge kind={kindOfDeliveryStatus(forward.latest.status)} label={t(`deliveries.status.${forward.latest.status}`)} />
                </div>
                <AttemptRail
                  attempts={forward.rail.attempts}
                  maxAttempts={forward.rail.maxAttempts}
                  size="full"
                  ariaLabel={t('deliveries.rail.label', { count: forward.attempts.length, total: forward.rail.maxAttempts })}
                />
                <ol className="relative space-y-4 border-l border-rail pl-4">
                  {forward.attempts.map((attempt) => {
                    const ok = attempt.status === 'SUCCESS';
                    return (
                      <li key={attempt.id} className="relative text-[12px]">
                        <span aria-hidden className={cn('absolute -left-[21px] top-1 h-2 w-2 rounded-full', ok ? 'bg-ok' : attempt.status === 'PENDING' || attempt.status === 'PROCESSING' ? 'bg-idle' : 'bg-halt')} />
                        <div className="flex flex-wrap items-baseline gap-x-3">
                          <span>{t('incomingEvents.detail.attempt', { number: attempt.attemptNumber })}</span>
                          {attempt.responseCode != null && <span className={cn('font-mono', ok ? 'text-ok' : 'text-halt')}>{attempt.responseCode}</span>}
                          {attempt.startedAt && <span className="ml-auto font-mono text-[11px] text-muted-foreground">{formatDateTime(attempt.startedAt)}</span>}
                        </div>
                        {attempt.errorMessage && <p className="mt-1 break-words text-halt">{attempt.errorMessage}</p>}
                        {attempt.nextRetryAt && (
                          <p className="mt-1 flex items-center gap-1 text-retry">
                            <Clock className="h-3 w-3" aria-hidden />
                            {t('incomingEvents.detail.nextRetry', { time: formatDateTime(attempt.nextRetryAt) })}
                          </p>
                        )}
                        {attempt.responseBodySnippet && (
                          <pre className="mt-1.5 max-h-24 overflow-auto whitespace-pre-wrap break-all bg-secondary px-2.5 py-1.5 font-mono text-[11px] text-muted-foreground">
                            {attempt.responseBodySnippet}
                          </pre>
                        )}
                        {attempt.requestHeadersJson && (
                          <details className="mt-1.5">
                            <summary className="cursor-pointer py-1 text-[11px] text-muted-foreground hover:text-foreground">
                              {t('incomingEvents.detail.attemptRequestHeaders')}
                            </summary>
                            <pre className="mt-1 max-h-24 overflow-auto whitespace-pre-wrap break-all bg-secondary p-2 font-mono text-[11px]">
                              {formatJson(attempt.requestHeadersJson)}
                            </pre>
                          </details>
                        )}
                        {attempt.requestBodySnippet && (
                          <details className="mt-1">
                            <summary className="cursor-pointer py-1 text-[11px] text-muted-foreground hover:text-foreground">
                              {t('incomingEvents.detail.attemptRequestBody')}
                            </summary>
                            <pre className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap break-all bg-secondary p-2 font-mono text-[11px]">
                              {formatJson(attempt.requestBodySnippet)}
                            </pre>
                          </details>
                        )}
                      </li>
                    );
                  })}
                </ol>
              </div>
            ))}
          </div>
        ))}
        {tab === 'body' && (event.bodyRaw
          ? <JsonView value={event.bodyRaw} title={<span className="font-mono">{event.contentType}</span>} maxHeight="max-h-[28rem]" />
          : <p className="text-[13px] text-muted-foreground">—</p>)}
        {tab === 'headers' && (event.headersJson
          ? <JsonView value={event.headersJson} maxHeight="max-h-[28rem]" />
          : <p className="text-[13px] text-muted-foreground">—</p>)}
      </div>

      {canReplay && (
        <div className="border-t border-rail pt-4">
          <Button size="sm" variant={event.verified === false ? 'outline' : 'default'} onClick={onReplay}>
            <RotateCcw className="h-3.5 w-3.5" /> {t('incomingEvents.replay.submit')}
          </Button>
        </div>
      )}
    </div>
  );
}

export default function IncomingEventsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const { canReplayIncomingEvents } = usePermissions();
  const wide = useWide();

  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [filterSourceId, setFilterSourceId] = useState('');
  const [verificationFilter, setVerificationFilter] = useState('');
  const [search, setSearch] = useState('');
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);
  const [replayEventId, setReplayEventId] = useState<string | null>(null);

  const {
    data: project, isLoading: projectLoading, isError: projectIsError, error: projectError, refetch: refetchProject,
  } = useProject(projectId);
  const {
    data: sourcesPage, isError: sourcesIsError, error: sourcesError, refetch: refetchSources,
  } = useIncomingSources(projectId, 0, 100);
  const sources = sourcesPage?.content ?? [];
  const {
    data: eventsPage, isLoading: eventsLoading, isError: eventsIsError, error: eventsError, refetch: refetchEvents,
  } = useIncomingEvents(projectId, { sourceId: filterSourceId || undefined, page, size: pageSize });
  const events = useMemo(() => eventsPage?.content ?? [], [eventsPage]);

  const visibleEvents = useMemo(() => events.filter((e) => {
    if (verificationFilter === 'verified' && e.verified !== true) return false;
    if (verificationFilter === 'failed' && e.verified !== false) return false;
    if (verificationFilter === 'none' && e.verified != null) return false;
    const q = search.trim().toLowerCase();
    if (q && !e.requestId.toLowerCase().includes(q) && !(e.sourceName ?? '').toLowerCase().includes(q)) return false;
    return true;
  }), [events, verificationFilter, search]);

  const selectedEvent = events.find((e) => e.id === selectedEventId) ?? null;

  // First load only; a page or source change keeps the previous rows (keepPreviousData).
  const loading = (projectLoading && !project) || (eventsLoading && !eventsPage);
  const isError = projectIsError || eventsIsError || sourcesIsError;
  const retry = () => { refetchProject(); refetchEvents(); refetchSources(); };

  const replayMutation = useReplayIncomingEvent(projectId!);
  const bulkReplayMutation = useBulkReplayIncomingEvents(projectId!);
  const replaying = replayMutation.isPending || bulkReplayMutation.isPending;

  const selectedCount = selectedIds.size;
  const allSelected = visibleEvents.length > 0 && visibleEvents.every((e) => selectedIds.has(e.id));
  const showPane = wide && selectedEvent !== null;

  const toggleRow = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    setSelectedIds(allSelected ? new Set() : new Set(visibleEvents.map((e) => e.id)));
  };

  const handleReplay = async () => {
    if (!replayEventId || !projectId) return;
    try {
      const result = await replayMutation.mutateAsync(replayEventId);
      showSuccess(t('incomingEvents.toast.replayed', { count: result.destinationsCount }));
      setReplayEventId(null);
    } catch (err) {
      showApiError(err, 'incomingEvents.toast.replayFailed');
    }
  };

  /** Bulk replay is scoped to one Source per call, so a mixed selection fans out. */
  const handleReplaySelected = async () => {
    if (selectedCount === 0) return;
    const bySource = new Map<string, string[]>();
    for (const event of events) {
      if (!selectedIds.has(event.id)) continue;
      const list = bySource.get(event.incomingSourceId) ?? [];
      list.push(event.id);
      bySource.set(event.incomingSourceId, list);
    }
    try {
      let replayed = 0;
      for (const [sourceId, eventIds] of bySource) {
        const result = await bulkReplayMutation.mutateAsync({ sourceId, eventIds });
        replayed += result.eventsReplayed;
      }
      showSuccess(t('incomingEvents.toast.bulkReplayed', { count: replayed }));
      setSelectedIds(new Set());
    } catch (err) {
      showApiError(err, 'incomingEvents.toast.replayFailed');
    }
  };

  if (loading) {
    return <PageSkeleton maxWidth="max-w-none"><SkeletonRows count={5} height="h-16" /></PageSkeleton>;
  }

  if (isError) {
    return (
      <div className="p-4 lg:p-8">
        <ErrorState error={projectError ?? eventsError ?? sourcesError} fallbackKey="incomingEvents.toast.loadFailed" onRetry={retry} />
      </div>
    );
  }

  const detail = selectedEvent && (
    <EventDetail
      key={selectedEvent.id}
      event={selectedEvent}
      projectId={projectId}
      canReplay={canReplayIncomingEvents}
      onReplay={() => setReplayEventId(selectedEvent.id)}
      onClose={() => setSelectedEventId(null)}
    />
  );

  return (
    <div className="p-4 lg:p-8">
      <PageHeader
        title={t('incomingEvents.pageTitle')}
        description={<Trans i18nKey="incomingEvents.subtitle" values={{ project: project?.name }} components={{ strong: <strong /> }} />}
      />

      <FilterBar>
        <FilterField id="incoming-source" label={t('incomingEvents.filters.source')} className="min-w-[12rem]">
          <Select id="incoming-source" value={filterSourceId} onChange={(e) => { setFilterSourceId(e.target.value); setPage(0); }}>
            <option value="">{t('incomingEvents.filters.allSources')}</option>
            {sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </Select>
        </FilterField>
        <FilterField id="incoming-verification" label={t('incomingEvents.columns.status')}>
          <Select id="incoming-verification" value={verificationFilter} onChange={(e) => setVerificationFilter(e.target.value)}>
            <option value="">{t('incomingEvents.filters.allVerifications')}</option>
            <option value="verified">{t('incomingEvents.verified')}</option>
            <option value="failed">{t('incomingEvents.failed')}</option>
            <option value="none">{t('incomingEvents.noVerification')}</option>
          </Select>
        </FilterField>
        <SearchField
          id="incoming-search"
          label={t('incomingEvents.columns.requestId')}
          placeholder={t('incomingEvents.filters.searchById')}
          value={search}
          onChange={setSearch}
        />
      </FilterBar>

      {events.length === 0 ? (
        <EmptyState
          icon={ArrowDownToLine}
          title={t('incomingEvents.noEvents')}
          description={sources.length === 0 ? t('incomingEvents.noEventsNoSourcesDesc') : t('incomingEvents.noEventsDesc')}
          action={sources.length === 0 ? (
            <Button onClick={() => navigate(`/admin/projects/${projectId}/incoming-sources`)}>
              <ArrowDownToLine className="h-4 w-4" /> {t('incomingEvents.createSourceFirst')}
            </Button>
          ) : undefined}
        />
      ) : (
        <div className={cn('animate-fade-in', showPane && 'grid grid-cols-[minmax(0,1fr)_28rem] gap-8')}>
          <div className="min-w-0">
            {canReplayIncomingEvents && (
              <SelectionBar count={selectedCount} onClear={() => setSelectedIds(new Set())}>
                <Button size="sm" onClick={handleReplaySelected} disabled={replaying}>
                  {replaying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
                  {t('incomingEvents.replaySelected', { count: selectedCount })}
                </Button>
              </SelectionBar>
            )}

            <Table className="text-[13px]">
              <TableHeader className="border-t border-rail">
                <TableRow>
                  {canReplayIncomingEvents && (
                    <TableHead className="w-10 px-2">
                      <SelectBox
                        checked={allSelected}
                        indeterminate={selectedCount > 0}
                        onChange={toggleAll}
                        label={t(allSelected ? 'common.deselectAll' : 'common.selectAll')}
                      />
                    </TableHead>
                  )}
                  <TableHead className="px-2">{t('incomingEvents.columns.received')}</TableHead>
                  <TableHead className="px-2">{t('incomingEvents.columns.source')}</TableHead>
                  <TableHead className={cn('px-2', showPane && 'hidden')}>{t('incomingEvents.columns.requestId')}</TableHead>
                  <TableHead className="px-2">{t('incomingEvents.columns.status')}</TableHead>
                  <TableHead className="w-[60px] px-2"><span className="sr-only">{t('common.actions')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {visibleEvents.map((event) => {
                  const verification = verificationOf(event);
                  const active = selectedEventId === event.id;
                  return (
                    <TableRow
                      key={event.id}
                      className={cn(
                        'group/row cursor-pointer',
                        active && 'bg-secondary hover:bg-secondary',
                        event.verified === false && !active && 'bg-halt-soft/40',
                      )}
                      data-state={selectedIds.has(event.id) ? 'selected' : undefined}
                      onClick={() => setSelectedEventId(active && wide ? null : event.id)}
                    >
                      {canReplayIncomingEvents && (
                        <TableCell className="px-2 py-2">
                          <SelectBox
                            checked={selectedIds.has(event.id)}
                            onChange={() => toggleRow(event.id)}
                            label={t('common.selectRow')}
                          />
                        </TableCell>
                      )}
                      <TableCell className="whitespace-nowrap px-2 py-2">
                        <span className="font-mono text-[12px] text-muted-foreground" title={formatDateTime(event.receivedAt)}>{formatRelativeTime(event.receivedAt)}</span>
                      </TableCell>
                      <TableCell className="px-2 py-2">
                        <span className="text-[13px]">{event.sourceName || event.incomingSourceId.slice(0, 8)}</span>
                      </TableCell>
                      <TableCell className={cn('px-2 py-2', showPane && 'hidden')}>
                        <span className="flex items-center gap-2">
                          <span className="font-mono text-[12px] text-muted-foreground">{event.method}</span>
                          <CopyId value={event.requestId} chars={12} />
                        </span>
                      </TableCell>
                      <TableCell className="px-2 py-2">
                        <span className="flex flex-col items-start gap-0.5">
                          <StatusBadge kind={verification.kind} label={t(verification.key)} />
                          {event.verificationError && (
                            <span className="block max-w-[200px] truncate text-[11px] text-muted-foreground" title={event.verificationError}>
                              {event.verificationError}
                            </span>
                          )}
                        </span>
                      </TableCell>
                      <TableCell className="px-2 py-2">
                        {canReplayIncomingEvents && (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            onClick={(e) => { e.stopPropagation(); setReplayEventId(event.id); }}
                            title={t('incomingEvents.replay.submit')}
                            aria-label={t('incomingEvents.replay.submit')}
                          >
                            <RotateCcw className="h-3.5 w-3.5" />
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>

            {(verificationFilter || search.trim()) && (
              <p className="mt-3 text-xs text-muted-foreground">
                {t('events.filters.clientSideNote', { shown: visibleEvents.length, count: events.length })}
              </p>
            )}

            <TablePagination
              page={page}
              pageSize={pageSize}
              totalElements={eventsPage?.totalElements ?? 0}
              totalPages={eventsPage?.totalPages ?? 0}
              onPageChange={setPage}
              onPageSizeChange={(size) => { setPageSize(size); setPage(0); }}
            />
          </div>
          {showPane && (
            <aside className="sticky top-4 h-[calc(100vh-6rem)] min-w-0 border-l border-rail pl-6">{detail}</aside>
          )}
        </div>
      )}

      {!wide && (
        <Sheet open={!!selectedEvent} onOpenChange={(open) => !open && setSelectedEventId(null)}>
          <SheetContent className="w-full overflow-y-auto p-5 sm:max-w-xl [&>button:last-child]:hidden">
            <SheetTitle className="sr-only">{t('incomingEvents.detail.title')}</SheetTitle>
            <SheetDescription className="sr-only">{selectedEvent?.requestId}</SheetDescription>
            {detail}
          </SheetContent>
        </Sheet>
      )}

      <AlertDialog open={!!replayEventId} onOpenChange={(open) => !open && setReplayEventId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('incomingEvents.replay.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('incomingEvents.replay.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <Callout className="mx-1">{t('incomingEvents.replay.idempotencyWarning')}</Callout>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={replaying}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleReplay} disabled={replaying}>
              {replaying && <Loader2 className="h-4 w-4 animate-spin" />}
              {replaying ? t('incomingEvents.replay.replaying') : t('incomingEvents.replay.submit')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
