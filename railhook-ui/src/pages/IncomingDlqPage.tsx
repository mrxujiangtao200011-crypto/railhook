import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { RotateCcw, Trash2, Loader2, CheckCircle2 } from 'lucide-react';
import { Trans, useTranslation } from 'react-i18next';
import { showApiError, showSuccess, showCriticalSuccess } from '../lib/toast';
import PageSkeleton from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import {
  useProject, useIncomingDlq, useIncomingDlqStats,
  useIncomingDlqRetry, useIncomingDlqBulkRetry, useIncomingDlqPurge,
} from '../api/queries';
import { Button } from '../components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import DangerConfirmDialog from '../components/DangerConfirmDialog';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { railFromCounts } from './attemptRailData';
import { AttemptCell, CopyId, SelectBox, SelectionBar } from './tableParts';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import { Dot } from '../components/port/p1/kit';

/** Retry re-forwards to the one failed Destination; a Time Machine replay fans out to all of them. */
export default function IncomingDlqPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageDlq } = usePermissions();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [showPurgeDialog, setShowPurgeDialog] = useState(false);

  const {
    data: project, isLoading: projectLoading, isError: projectIsError, error: projectError, refetch: refetchProject,
  } = useProject(projectId);
  const {
    data: dlqData, isLoading: dlqLoading, isError: dlqIsError, error: dlqError, refetch: refetchDlq,
  } = useIncomingDlq(projectId, page, pageSize);
  const { data: stats, refetch: refetchStats } = useIncomingDlqStats(projectId);

  const items = useMemo(() => dlqData?.content ?? [], [dlqData]);
  const totalElements = dlqData?.totalElements ?? 0;
  const totalPages = dlqData?.totalPages ?? 0;

  // First load only; a page change keeps the previous rows (keepPreviousData).
  const loading = (projectLoading && !project) || (dlqLoading && !dlqData);
  const isError = projectIsError || dlqIsError;
  const retry = () => { refetchProject(); refetchDlq(); refetchStats(); };

  const retrySingleMutation = useIncomingDlqRetry(projectId!);
  const retryBulkMutation = useIncomingDlqBulkRetry(projectId!);
  const purgeMutation = useIncomingDlqPurge(projectId!);
  const retrying = retrySingleMutation.isPending || retryBulkMutation.isPending;
  const purging = purgeMutation.isPending;

  const selectedCount = selectedIds.size;
  const allSelected = items.length > 0 && items.every((i) => selectedIds.has(i.forwardAttemptId));

  const handleRetrySingle = async (forwardAttemptId: string) => {
    try {
      await retrySingleMutation.mutateAsync(forwardAttemptId);
      showSuccess(t('incomingDlq.toast.retried'));
      refetchStats();
    } catch (err: unknown) {
      showApiError(err, 'incomingDlq.toast.retryFailed');
    }
  };

  const handleRetrySelected = async () => {
    if (selectedCount === 0) return;
    try {
      const result = await retryBulkMutation.mutateAsync(Array.from(selectedIds));
      showSuccess(t('incomingDlq.toast.bulkRetried', { count: result.retried }));
      setSelectedIds(new Set());
      refetchStats();
    } catch (err: unknown) {
      showApiError(err, 'incomingDlq.toast.bulkRetryFailed');
    }
  };

  const handlePurgeAll = async () => {
    try {
      const result = await purgeMutation.mutateAsync();
      showCriticalSuccess(t('incomingDlq.toast.purged', { count: result.purged }));
      setShowPurgeDialog(false);
      setSelectedIds(new Set());
      refetchStats();
    } catch (err: unknown) {
      showApiError(err, 'incomingDlq.toast.purgeFailed');
    }
  };

  const toggleRow = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    setSelectedIds(allSelected ? new Set() : new Set(items.map((i) => i.forwardAttemptId)));
  };

  if (loading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <div className="h-[300px] animate-pulse bg-muted" />
      </PageSkeleton>
    );
  }

  if (isError) {
    return (
      <div className="p-4 lg:p-8">
        <ErrorState error={projectError ?? dlqError} fallbackKey="incomingDlq.toast.loadFailed" onRetry={retry} />
      </div>
    );
  }

  return (
    <div className="p-4 lg:p-8">
      <PageHeader
        title={t('incomingDlq.pageTitle')}
        description={<Trans i18nKey="incomingDlq.subtitle" values={{ project: project?.name }} components={{ strong: <strong /> }} />}
        actions={
          <PermissionGate allowed={canManageDlq}>
            <VerificationGate>
              <Button variant="destructive" onClick={() => setShowPurgeDialog(true)} disabled={!stats?.totalItems}>
                <Trash2 className="h-3.5 w-3.5" /> {t('incomingDlq.purgeAll')}
              </Button>
            </VerificationGate>
          </PermissionGate>
        }
      />

      {stats && stats.totalItems > 0 && (
        <p className="mb-6 flex items-center gap-2.5 text-sm">
          <Dot tone="halt" />
          {t('incomingDlq.statsLine', { total: stats.totalItems, day: stats.last24Hours, week: stats.last7Days })}
        </p>
      )}

      {items.length === 0 ? (
        <EmptyState icon={CheckCircle2} title={t('incomingDlq.noItems')} description={t('incomingDlq.noItemsDesc')} docsLink="outgoing/retries" />
      ) : (
        <div className="animate-fade-in">
          <PermissionGate allowed={canManageDlq}>
            <SelectionBar count={selectedCount} onClear={() => setSelectedIds(new Set())}>
              <VerificationGate>
                <Button size="sm" onClick={handleRetrySelected} disabled={retrying}>
                  {retrying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
                  {t('incomingDlq.retrySelected', { count: selectedCount })}
                </Button>
              </VerificationGate>
            </SelectionBar>
          </PermissionGate>

          <Table className="text-[13px]">
              <TableHeader className="border-t border-rail">
                <TableRow>
                  {canManageDlq && (
                    <TableHead className="w-10">
                      <SelectBox
                        checked={allSelected}
                        indeterminate={selectedCount > 0}
                        onChange={toggleAll}
                        label={t(allSelected ? 'common.deselectAll' : 'common.selectAll')}
                      />
                    </TableHead>
                  )}
                  <TableHead className="px-2">{t('incomingDlq.columns.source')}</TableHead>
                  <TableHead className="px-2">{t('incomingDlq.columns.destination')}</TableHead>
                  <TableHead className="px-2">{t('incomingDlq.columns.lastResponse')}</TableHead>
                  <TableHead className="px-2">{t('incomingDlq.columns.attempts')}</TableHead>
                  <TableHead className="px-2">{t('incomingDlq.columns.failedAt')}</TableHead>
                  <TableHead className="px-2">{t('incomingDlq.columns.eventId')}</TableHead>
                  {canManageDlq && <TableHead className="w-[60px]"><span className="sr-only">{t('common.actions')}</span></TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((item) => {
                  const attemptCount = item.attemptNumber ?? 0;
                  const ladderLength = item.maxAttempts ?? attemptCount;
                  const rail = railFromCounts(attemptCount, ladderLength, 'DLQ');
                  return (
                    <TableRow
                      key={item.forwardAttemptId}
                      className="group/row"
                      data-state={selectedIds.has(item.forwardAttemptId) ? 'selected' : undefined}
                    >
                      {canManageDlq && (
                        <TableCell>
                          <SelectBox
                            checked={selectedIds.has(item.forwardAttemptId)}
                            onChange={() => toggleRow(item.forwardAttemptId)}
                            label={t('common.selectRow')}
                          />
                        </TableCell>
                      )}
                      <TableCell className="px-2 py-2.5">
                        <span className="block text-[13px]">{item.sourceName || '—'}</span>
                        <span className="text-[11px] text-muted-foreground">{t('incomingDlq.ladderExhausted', { count: attemptCount })}</span>
                      </TableCell>
                      <TableCell className="px-2 py-2.5">
                        <span className="block max-w-[240px] truncate font-mono text-[12px] text-muted-foreground" title={item.destinationUrl}>
                          {item.destinationUrl || '—'}
                        </span>
                      </TableCell>
                      <TableCell className="px-2 py-2.5">
                        <span className="block max-w-[260px] truncate font-mono text-[12px] text-halt" title={item.lastError ?? undefined}>
                          {item.responseCode != null && <span className="mr-2">{item.responseCode}</span>}
                          {item.lastError || (item.responseCode == null ? t('incomingDlq.unknownError') : '')}
                        </span>
                      </TableCell>
                      <TableCell className="px-2 py-2.5">
                        <AttemptCell
                          rail={rail.attempts}
                          maxAttempts={rail.maxAttempts}
                          attemptCount={attemptCount}
                          ladderLength={ladderLength}
                        />
                      </TableCell>
                      <TableCell className="whitespace-nowrap px-2 py-2.5">
                        {(item.failedAt ?? item.createdAt) && (
                          <span className="font-mono text-[12px] text-muted-foreground" title={formatDateTime(item.failedAt ?? item.createdAt ?? '')}>
                            {formatRelativeTime(item.failedAt ?? item.createdAt ?? '')}
                          </span>
                        )}
                      </TableCell>
                      <TableCell className="px-2 py-2.5"><CopyId value={item.incomingEventId} /></TableCell>
                      {canManageDlq && (
                        <TableCell>
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            onClick={() => handleRetrySingle(item.forwardAttemptId)}
                            disabled={retrying}
                            title={t('incomingDlq.retryOne')}
                            aria-label={t('incomingDlq.retryOne')}
                          >
                            <RotateCcw className="h-3.5 w-3.5" />
                          </Button>
                        </TableCell>
                      )}
                    </TableRow>
                  );
                })}
              </TableBody>
          </Table>

          <TablePagination
            page={page}
            pageSize={pageSize}
            totalElements={totalElements}
            totalPages={totalPages}
            onPageChange={setPage}
            onPageSizeChange={(size) => { setPageSize(size); setPage(0); }}
          />
        </div>
      )}

      <DangerConfirmDialog
        open={showPurgeDialog}
        onOpenChange={setShowPurgeDialog}
        title={t('incomingDlq.purgeDialog.title')}
        description={t('incomingDlq.purgeDialog.description', { count: stats?.totalItems })}
        confirmName={project?.name || ''}
        impact={[
          t('incomingDlq.purgeDialog.impactItems', { count: stats?.totalItems || 0 }),
          t('incomingDlq.purgeDialog.impactIrreversible'),
        ]}
        onConfirm={handlePurgeAll}
        loading={purging}
        confirmLabel={t('incomingDlq.purgeAll')}
      />
    </div>
  );
}
