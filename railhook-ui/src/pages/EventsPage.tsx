import { Fragment, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ChevronDown, ChevronRight, Loader2, Plus, Radio, Share2 } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import { useEvents, useProject } from '../api/queries';
import PageSkeleton from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import StatusBadge, { type StatusKind } from '../components/StatusBadge';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '../components/ui/button';
import { Select } from '../components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { SortableTableHead, useSort } from '../components/ui/sortable-table-head';
import { TablePagination } from '../components/ui/table-pagination';
import SendTestEventModal from '../components/SendTestEventModal';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { debugLinksApi } from '../api/debugLinks.api';
import { CopyId, FilterBar, FilterField, SearchField, SORTABLE_HEAD_CLASS } from './tableParts';
import type { DeliveryStatusCounts } from '../types/api.types';
import { useDebounced } from '../hooks/useDebounced';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import { cn } from '../lib/utils';
import { JsonView, SegmentBar } from '../components/port/p1/kit';

type EventStatus = 'delivered' | 'owed' | 'abandoned' | 'unsubscribed';

function deliveredOf(counts: DeliveryStatusCounts | undefined) {
  if (!counts) return { delivered: 0, total: 0 };
  return {
    delivered: counts.success,
    total: counts.pending + counts.processing + counts.success + counts.failed + counts.dlq,
  };
}

function statusOf(counts: DeliveryStatusCounts | undefined): EventStatus {
  if (!counts || deliveredOf(counts).total === 0) return 'unsubscribed';
  if (counts.dlq > 0 || counts.failed > 0) return 'abandoned';
  if (counts.pending > 0 || counts.processing > 0) return 'owed';
  return 'delivered';
}

/** Exported for the locale test: the Record guarantees these keys are complete. */
export const STATUS_KIND: Record<EventStatus, StatusKind> = {
  delivered: 'ok',
  owed: 'retry',
  abandoned: 'halt',
  unsubscribed: 'idle',
};

const STATUS_FILTERS: EventStatus[] = ['delivered', 'owed', 'abandoned', 'unsubscribed'];

export default function EventsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const { sort, toggle: toggleSort, param: sortParam } = useSort('createdAt', 'desc');
  const [showSendModal, setShowSendModal] = useState(false);
  const { canSendEvents, canCreateDebugLinks } = usePermissions();
  const [sharingEventId, setSharingEventId] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const debouncedSearch = useDebounced(search.trim());

  useEffect(() => setPage(0), [debouncedSearch]);

  const { data: project, isError: projectIsError, error: projectError, refetch: refetchProject } = useProject(projectId);

  const {
    data: eventsData, isLoading: eventsLoading, isError: eventsIsError, error: eventsError, refetch: refetchEvents,
  } = useEvents(projectId, page, pageSize, sortParam, debouncedSearch || undefined);
  const events = useMemo(() => eventsData?.content ?? [], [eventsData]);
  const totalElements = eventsData?.totalElements ?? 0;
  const totalPages = eventsData?.totalPages ?? 0;

  const rows = useMemo(() => events.map((event) => ({
    event,
    delivered: deliveredOf(event.deliveryCounts),
    status: statusOf(event.deliveryCounts),
  })), [events]);

  const visibleRows = statusFilter ? rows.filter((r) => r.status === statusFilter) : rows;

  // Skeleton on first load only, so the search input is not unmounted while typing.
  const loading = eventsLoading && !eventsData;
  const isError = projectIsError || eventsIsError;
  const retry = () => { refetchProject(); refetchEvents(); };

  const handleShareDebugLink = async (eventId: string) => {
    if (!projectId) return;
    try {
      setSharingEventId(eventId);
      const link = await debugLinksApi.create(projectId, eventId);
      await navigator.clipboard.writeText(link.shareUrl);
      showSuccess(t('debugLinks.copied'));
    } catch (err: any) {
      showApiError(err, 'debugLinks.createFailed');
    } finally {
      setSharingEventId(null);
    }
  };

  if (loading) {
    return <PageSkeleton maxWidth="max-w-none" />;
  }

  if (isError) {
    return (
      <div className="p-4 lg:p-6">
        <ErrorState error={projectError ?? eventsError} fallbackKey="events.toast.loadFailed" onRetry={retry} />
      </div>
    );
  }

  const sendAction = (
    <PermissionGate allowed={canSendEvents}>
      <VerificationGate>
        <Button onClick={() => setShowSendModal(true)}>
          <Plus className="h-4 w-4" /> {t('events.sendTest')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <div className="p-4 lg:p-8">
      <PageHeader
        description={<Trans i18nKey="events.subtitle" values={{ project: project?.name }} components={{ strong: <strong /> }} />}
        actions={sendAction}
      />

      <FilterBar>
        <FilterField id="event-status" label={t('events.filters.deliveryStatus')}>
          <Select id="event-status" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="">{t('events.filters.allStatuses')}</option>
            {STATUS_FILTERS.map((s) => (
              <option key={s} value={s}>{t(`events.deliveryStatus.${s}`)}</option>
            ))}
          </Select>
        </FilterField>
        <SearchField
          id="event-search"
          label={t('events.filters.eventType')}
          placeholder={t('events.filters.eventTypePlaceholder')}
          value={search}
          onChange={setSearch}
        />
      </FilterBar>

      {events.length === 0 ? (
        <EmptyState
          icon={Radio}
          title={search ? t('common.noResults') : t('events.noEvents')}
          description={search ? t('events.noMatchDesc') : t('events.noEventsDesc')}
          action={search ? (
            <Button variant="outline" onClick={() => setSearch('')}>{t('common.clearSearch')}</Button>
          ) : sendAction}
          docsLink={search ? undefined : 'api-reference'}
        />
      ) : (
        <div className="animate-fade-in">
          <Table className="text-[13px]">
            <TableHeader className="border-t border-rail">
              <TableRow>
                <TableHead className="hidden w-10 px-2 sm:table-cell"><span className="sr-only">{t('events.details.title')}</span></TableHead>
                <TableHead className="px-2">{t('deliveries.columns.status')}</TableHead>
                <SortableTableHead field="eventType" sort={sort} onSort={toggleSort} className={cn(SORTABLE_HEAD_CLASS, 'px-2')}>{t('events.eventType')}</SortableTableHead>
                <TableHead className="px-2">{t('events.deliveriesCount')}</TableHead>
                <TableHead className="px-2">{t('events.eventId')}</TableHead>
                <SortableTableHead field="createdAt" sort={sort} onSort={toggleSort} className={cn(SORTABLE_HEAD_CLASS, 'px-2')}>{t('events.created')}</SortableTableHead>
                <TableHead className="w-[60px] px-2"><span className="sr-only">{t('common.actions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visibleRows.map(({ event, delivered, status }) => {
                const open = expanded === event.id;
                const c = event.deliveryCounts;
                return (
                  <Fragment key={event.id}>
                    <TableRow
                      className={cn(
                        'group/row cursor-pointer',
                        open && 'bg-secondary hover:bg-secondary',
                        status === 'abandoned' && !open && 'bg-halt-soft/40',
                      )}
                      onClick={() => setExpanded(open ? null : event.id)}
                    >
                      <TableCell className="hidden px-2 py-2 text-muted-foreground sm:table-cell">
                        {open ? <ChevronDown className="h-3.5 w-3.5" aria-hidden /> : <ChevronRight className="h-3.5 w-3.5" aria-hidden />}
                      </TableCell>
                      <TableCell className="px-2 py-2">
                        <StatusBadge kind={STATUS_KIND[status]} label={t(`events.deliveryStatus.${status}`)} />
                      </TableCell>
                      <TableCell className="px-2 py-2">
                        <Link
                          to={`/admin/projects/${projectId}/events/${event.id}`}
                          onClick={(e) => e.stopPropagation()}
                          className="break-all font-mono text-[12px] underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                        >
                          {event.eventType}
                        </Link>
                      </TableCell>
                      <TableCell className="px-2 py-2">
                        <span className="flex items-center gap-3">
                          {c && delivered.total > 0 && (
                            <SegmentBar
                              className="w-16 max-sm:hidden"
                              parts={[
                                { value: c.success, className: 'bg-ok' },
                                { value: c.pending + c.processing, className: 'bg-idle/50' },
                                { value: c.failed, className: 'bg-retry' },
                                { value: c.dlq, className: 'bg-halt' },
                              ]}
                            />
                          )}
                          <Link
                            to={`/admin/projects/${projectId}/deliveries?eventId=${event.id}`}
                            onClick={(e) => e.stopPropagation()}
                            className={cn(
                              'whitespace-nowrap font-mono text-[12px] underline-offset-4 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                              status === 'abandoned' ? 'text-halt' : 'text-muted-foreground',
                            )}
                          >
                            {t('events.deliveredOf', delivered)}
                          </Link>
                        </span>
                      </TableCell>
                      <TableCell className="px-2 py-2">
                        <CopyId value={event.id} to={`/admin/projects/${projectId}/events/${event.id}`} />
                      </TableCell>
                      <TableCell className="whitespace-nowrap px-2 py-2">
                        <span className="text-[13px]" title={formatDateTime(event.createdAt)}>{formatRelativeTime(event.createdAt)}</span>
                      </TableCell>
                      <TableCell className="px-2 py-2">
                        {canCreateDebugLinks && (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            onClick={(e) => { e.stopPropagation(); handleShareDebugLink(event.id); }}
                            disabled={sharingEventId === event.id}
                            title={t('debugLinks.share')}
                            aria-label={t('debugLinks.share')}
                          >
                            {sharingEventId === event.id ? (
                              <Loader2 className="h-3.5 w-3.5 animate-spin" />
                            ) : (
                              <Share2 className="h-3.5 w-3.5" />
                            )}
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                    {open && (
                      <TableRow className="bg-secondary/40 hover:bg-secondary/40">
                        <TableCell colSpan={7} className="px-2 pb-4 pt-1">
                          <div className="w-full min-w-0 text-left">
                            <JsonView value={event.payload} title={<span className="font-mono">{event.id}</span>} maxHeight="max-h-72" />
                            <div className="mt-3 flex flex-wrap gap-2">
                              <Button size="sm" asChild>
                                <Link to={`/admin/projects/${projectId}/events/${event.id}`}>{t('events.details.openFull')}</Link>
                              </Button>
                              <Button size="sm" variant="outline" asChild>
                                <Link to={`/admin/projects/${projectId}/deliveries?eventId=${event.id}`}>{t('events.details.deliveries')}</Link>
                              </Button>
                            </div>
                          </div>
                        </TableCell>
                      </TableRow>
                    )}
                  </Fragment>
                );
              })}
            </TableBody>
          </Table>

          {statusFilter && (
            <p className="mt-3 text-xs text-muted-foreground">
              {t('events.filters.clientSideNote', { shown: visibleRows.length, count: rows.length })}
            </p>
          )}

          <TablePagination
            page={page}
            pageSize={pageSize}
            totalElements={totalElements}
            totalPages={totalPages}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
          />
        </div>
      )}

      <SendTestEventModal
        projectId={projectId!}
        open={showSendModal}
        onClose={() => setShowSendModal(false)}
        onSuccess={() => queryClient.invalidateQueries({ queryKey: ['events', projectId] })}
      />
    </div>
  );
}
