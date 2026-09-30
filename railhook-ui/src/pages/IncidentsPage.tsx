import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  ArrowRight, Flame, Loader2, MessageSquare, Plus, RotateCcw,
  Search as SearchIcon, Send, XCircle, CheckCircle2,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import {
  useIncidents, useIncident, useCreateIncident, useUpdateIncident, useAddTimelineEntry, useOpenIncidentCount,
} from '../api/queries';
import type { IncidentStatus, IncidentTimelineType } from '../api/incidents.api';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';
import { Textarea } from '../components/ui/textarea';
import { TablePagination } from '../components/ui/table-pagination';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { cn } from '../lib/utils';
import {
  STATUS_FILL, STATUS_TEXT, formatCompact, kindOfIncidentStatus, kindOfSeverity,
} from '../components/charts';
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '../components/ui/sheet';
import { Dot, Segmented, useWide } from '../components/port/p1/kit';
import type { IncidentResponse } from '../api/incidents.api';

const SEVERITY_VALUES = ['INFO', 'WARNING', 'CRITICAL'] as const;

const TIMELINE_ICON: Record<IncidentTimelineType, React.ElementType> = {
  FAILURE: XCircle,
  RETRY: RotateCcw,
  REPLAY: Send,
  NOTE: MessageSquare,
  STATUS_CHANGE: ArrowRight,
};

const TIMELINE_KIND = {
  FAILURE: 'halt',
  RETRY: 'retry',
  REPLAY: 'retry',
  NOTE: 'idle',
  STATUS_CHANGE: 'idle',
} as const;

function IncidentDetail({
  incident, projectId, canManage, onStatus, onSaveRca, onAddNote, onClose,
}: {
  incident: IncidentResponse;
  projectId: string;
  canManage: boolean;
  onStatus: (status: IncidentStatus) => void;
  onSaveRca: (notes: string) => void;
  onAddNote: () => void;
  onClose?: () => void;
}) {
  const { t } = useTranslation();
  const severityKind = kindOfSeverity(incident.severity);
  return (
    <div className="min-w-0">
      <div className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <h3 className="break-words text-[20px] font-normal leading-snug tracking-[-0.01em]">{incident.title}</h3>
          <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1">
            <StatusBadge kind={kindOfIncidentStatus(incident.status)} label={t(`incidents.statuses.${incident.status}`)} />
            <span className={cn('text-[13px]', STATUS_TEXT[severityKind])}>{t(`alerts.severities.${incident.severity}`)}</span>
            <span className="font-mono text-[12px] text-muted-foreground" title={formatDateTime(incident.createdAt)}>{formatRelativeTime(incident.createdAt)}</span>
          </div>
          {incident.resolvedAt && (
            <p className="mt-1 text-[13px] text-muted-foreground">{t('incidents.resolvedAtLabel', { time: formatDateTime(incident.resolvedAt) })}</p>
          )}
        </div>
        {onClose && (
          <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label={t('common.close')} title={t('common.close')} className="-mr-2 text-muted-foreground">
            <XCircle className="h-4 w-4" />
          </Button>
        )}
      </div>

      <div className="mt-5 flex flex-wrap items-center gap-2">
        <Button variant="outline" size="sm" asChild>
          <Link to={`/admin/projects/${projectId}/deliveries?status=FAILED`}>
            <SearchIcon className="h-3.5 w-3.5" />
            {t('incidents.investigateDeliveries')}
          </Link>
        </Button>
        {canManage && (
          <>
            {incident.status !== 'INVESTIGATING' && incident.status !== 'RESOLVED' && (
              <Button variant="outline" size="sm" onClick={() => onStatus('INVESTIGATING')}>
                <SearchIcon className="h-3.5 w-3.5" /> {t('incidents.investigate')}
              </Button>
            )}
            {incident.status !== 'RESOLVED' && (
              <Button size="sm" onClick={() => onStatus('RESOLVED')}>
                <CheckCircle2 className="h-3.5 w-3.5" /> {t('incidents.resolve')}
              </Button>
            )}
            {incident.status === 'RESOLVED' && (
              <Button variant="outline" size="sm" onClick={() => onStatus('OPEN')}>
                <XCircle className="h-3.5 w-3.5" /> {t('incidents.reopen')}
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={onAddNote}>
              <MessageSquare className="h-3.5 w-3.5" /> {t('incidents.addNote')}
            </Button>
          </>
        )}
      </div>

      <section className="mt-8">
        <h4 className="mb-3 text-[15px] font-medium">{t('incidents.timeline')}</h4>
        {incident.timeline && incident.timeline.length > 0 ? (
          <ol className="relative border-l border-rail pl-6">
            {incident.timeline.map((entry) => {
              const EntryIcon = TIMELINE_ICON[entry.entryType] ?? ArrowRight;
              const kind = TIMELINE_KIND[entry.entryType] ?? 'idle';
              return (
                <li key={entry.id} className="relative pb-5 last:pb-0">
                  <span className="absolute -left-[33px] top-0 flex h-4 w-4 items-center justify-center bg-background">
                    <EntryIcon className={cn('h-3 w-3', STATUS_TEXT[kind])} aria-hidden />
                  </span>
                  <p className="text-[13px]">{entry.title}</p>
                  {entry.detail && <p className="mt-0.5 break-words text-[13px] text-muted-foreground">{entry.detail}</p>}
                  <p className="mt-0.5 font-mono text-[11px] text-muted-foreground" title={formatDateTime(entry.createdAt)}>{formatRelativeTime(entry.createdAt)}</p>
                  {entry.deliveryId && (
                    <Link
                      to={`/admin/projects/${projectId}/deliveries?deliveryId=${entry.deliveryId}`}
                      className="mt-0.5 inline-flex min-h-[32px] items-center font-mono text-[11px] text-muted-foreground hover:text-foreground hover:underline"
                    >
                      {entry.deliveryId}
                    </Link>
                  )}
                </li>
              );
            })}
          </ol>
        ) : (
          <p className="text-[13px] text-muted-foreground">—</p>
        )}
      </section>

      <section className="mt-8 space-y-2">
        <Label htmlFor={`rca-${incident.id}`} className="text-[15px] font-medium">{t('incidents.rcaNotes')}</Label>
        <Textarea
          key={incident.id}
          id={`rca-${incident.id}`}
          className="min-h-[96px] text-sm"
          placeholder={t('incidents.rcaPlaceholder')}
          defaultValue={incident.rcaNotes || ''}
          readOnly={!canManage}
          onBlur={(e) => {
            const val = e.target.value;
            if (canManage && val !== (incident.rcaNotes || '')) onSaveRca(val);
          }}
        />
      </section>
    </div>
  );
}

export default function IncidentsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageEndpoints } = usePermissions();

  const [openOnly, setOpenOnly] = useState(true);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const wide = useWide();
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [showNoteDialog, setShowNoteDialog] = useState<string | null>(null);

  const [formTitle, setFormTitle] = useState('');
  const [formSeverity, setFormSeverity] = useState<string>('WARNING');
  const [noteTitle, setNoteTitle] = useState('');
  const [noteDetail, setNoteDetail] = useState('');

  const {
    data: incidentsData, isLoading, isError, error, refetch,
  } = useIncidents(projectId, openOnly, page, pageSize);
  const { data: openCount } = useOpenIncidentCount(projectId);
  const createIncident = useCreateIncident(projectId!);
  const updateIncident = useUpdateIncident(projectId!);
  const addTimeline = useAddTimelineEntry(projectId!);
  const incidents = incidentsData?.content ?? [];
  const activeId = selectedId ?? (wide ? incidents[0]?.id ?? null : null);
  const { data: detailIncident } = useIncident(projectId, activeId ?? undefined);
  // Server counts span the project: counting one filtered page undercounted critical incidents.
  const openIncidents = openCount?.count ?? 0;
  const investigating = openCount?.investigating ?? 0;
  const critical = openCount?.critical ?? 0;

  const handleCreate = async () => {
    try {
      await createIncident.mutateAsync({ title: formTitle, severity: formSeverity });
      showSuccess(t('incidents.toast.created'));
      setShowCreateDialog(false);
      setFormTitle('');
      setFormSeverity('WARNING');
    } catch (err: any) {
      showApiError(err, 'incidents.toast.createFailed');
    }
  };

  const handleStatusChange = async (incidentId: string, status: IncidentStatus) => {
    try {
      await updateIncident.mutateAsync({ incidentId, data: { status } });
      showSuccess(t('incidents.toast.statusUpdated'));
    } catch (err: any) {
      showApiError(err, 'incidents.toast.updateFailed');
    }
  };

  const handleSaveRca = async (incidentId: string, rcaNotes: string) => {
    try {
      await updateIncident.mutateAsync({ incidentId, data: { rcaNotes } });
      showSuccess(t('incidents.toast.rcaSaved'));
    } catch (err: any) {
      showApiError(err, 'incidents.toast.updateFailed');
    }
  };

  const handleAddNote = async () => {
    if (!showNoteDialog) return;
    try {
      await addTimeline.mutateAsync({
        incidentId: showNoteDialog,
        data: { entryType: 'NOTE' as IncidentTimelineType, title: noteTitle, detail: noteDetail || undefined },
      });
      showSuccess(t('incidents.toast.noteAdded'));
      setShowNoteDialog(null);
      setNoteTitle('');
      setNoteDetail('');
    } catch (err: any) {
      showApiError(err, 'incidents.toast.addNoteFailed');
    }
  };

  if (isLoading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <SkeletonCards count={4} height="h-16" cols="grid-cols-1" />
      </PageSkeleton>
    );
  }

  const detail = activeId && detailIncident && detailIncident.id === activeId && (
    <IncidentDetail
      incident={detailIncident}
      projectId={projectId!}
      canManage={canManageEndpoints}
      onStatus={(status) => handleStatusChange(detailIncident.id, status)}
      onSaveRca={(notes) => handleSaveRca(detailIncident.id, notes)}
      onAddNote={() => setShowNoteDialog(detailIncident.id)}
      onClose={wide ? undefined : () => setSelectedId(null)}
    />
  );

  return (
    <div className="p-4 lg:p-8">
      <PageHeader
        title={t('incidents.title')}
        description={t('incidents.subtitle')}
        actions={
          <PermissionGate allowed={canManageEndpoints}>
            <VerificationGate>
              <Button onClick={() => setShowCreateDialog(true)}>
                <Plus className="h-4 w-4" /> {t('incidents.create')}
              </Button>
            </VerificationGate>
          </PermissionGate>
        }
      />

      <div className="mb-5 flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        {openIncidents === 0 ? (
          <p className="flex items-center gap-2 text-sm"><Dot tone="ok" />{t('incidents.tiles.allClear')}</p>
        ) : (
          <dl className="flex flex-wrap items-baseline gap-x-6 gap-y-1 text-sm">
            <div className="flex items-baseline gap-2">
              <dt className="text-muted-foreground">{t('incidents.tiles.open')}</dt>
              <dd className="tabular-nums text-halt">{formatCompact(openIncidents)}</dd>
            </div>
            <div className="flex items-baseline gap-2">
              <dt className="text-muted-foreground">{t('incidents.tiles.investigating')}</dt>
              <dd className="tabular-nums">{formatCompact(investigating)}</dd>
            </div>
            <div className="flex items-baseline gap-2">
              <dt className="text-muted-foreground">{t('incidents.tiles.critical')}</dt>
              <dd className={cn('tabular-nums', critical > 0 && 'text-halt')}>{formatCompact(critical)}</dd>
            </div>
          </dl>
        )}
        <Segmented
          label={t('incidents.filterLabel')}
          value={openOnly ? 'open' : 'all'}
          onChange={(v) => { setOpenOnly(v === 'open'); setPage(0); setSelectedId(null); }}
          options={[
            { value: 'open', label: t('incidents.openOnly') },
            { value: 'all', label: t('incidents.showAll') },
          ]}
        />
      </div>

      {isError ? (
        <ErrorState error={error} fallbackKey="incidents.loadFailed" onRetry={() => refetch()} />
      ) : incidents.length === 0 ? (
        <EmptyState
          icon={Flame}
          title={t('incidents.empty')}
          description={t('incidents.emptyDesc')}
          action={
            <PermissionGate allowed={canManageEndpoints}>
              <VerificationGate>
                <Button onClick={() => setShowCreateDialog(true)}>
                  <Plus className="h-4 w-4" /> {t('incidents.create')}
                </Button>
              </VerificationGate>
            </PermissionGate>
          }
        />
      ) : (
        <div className={cn('animate-fade-in', wide && 'grid grid-cols-[22rem_minmax(0,1fr)] gap-8')}>
          <div className="min-w-0">
            <ul className="border-t border-rail">
              {incidents.map((incident) => {
                const active = activeId === incident.id;
                const severityKind = kindOfSeverity(incident.severity);
                return (
                  <li key={incident.id} className="border-b border-rail">
                    <button
                      type="button"
                      className={cn(
                        'relative flex w-full items-start gap-3 py-3 pl-3 pr-2 text-left transition-colors',
                        active ? 'bg-secondary' : 'hover:bg-secondary/50',
                      )}
                      onClick={() => setSelectedId(incident.id)}
                      aria-current={active ? 'true' : undefined}
                    >
                      <span aria-hidden className={cn('absolute bottom-2 left-0 top-2 w-[2px]', STATUS_FILL[severityKind])} />
                      <span className="min-w-0 flex-1">
                        <span className="block break-words text-[13px] font-medium leading-snug">{incident.title}</span>
                        <span className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1">
                          <StatusBadge kind={kindOfIncidentStatus(incident.status)} label={t(`incidents.statuses.${incident.status}`)} />
                          <span className={cn('text-[12px]', STATUS_TEXT[severityKind])}>{t(`alerts.severities.${incident.severity}`)}</span>
                          <span className="font-mono text-[11px] text-muted-foreground">{formatRelativeTime(incident.createdAt)}</span>
                        </span>
                        {incident.alertRuleName && (
                          <span className="mt-1 block text-[12px] text-muted-foreground">
                            {t('incidents.openedByRule', { name: incident.alertRuleName })}
                          </span>
                        )}
                        {incident.autoResolved && (
                          <span className="mt-0.5 block text-[12px] text-ok">{t('incidents.autoResolved')}</span>
                        )}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>

            {incidentsData && (
              <TablePagination
                page={page}
                pageSize={pageSize}
                totalElements={incidentsData.totalElements}
                totalPages={incidentsData.totalPages}
                onPageChange={setPage}
                onPageSizeChange={setPageSize}
              />
            )}
          </div>
          {wide && <div className="min-w-0 border-l border-rail pl-8">{detail}</div>}
        </div>
      )}

      {!wide && (
        <Sheet open={!!selectedId} onOpenChange={(open) => !open && setSelectedId(null)}>
          <SheetContent side="right" className="w-full overflow-y-auto p-5 sm:max-w-lg [&>button:last-child]:hidden">
            <SheetTitle className="sr-only">{detailIncident?.title}</SheetTitle>
            <SheetDescription className="sr-only">{t('incidents.timeline')}</SheetDescription>
            {detail}
          </SheetContent>
        </Sheet>
      )}

      <Dialog open={showCreateDialog} onOpenChange={setShowCreateDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('incidents.createDialog.title')}</DialogTitle>
            <DialogDescription>{t('incidents.createDialog.desc')}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label htmlFor="incident-title">{t('incidents.form.title')}</Label>
              <Input
                id="incident-title"
                value={formTitle}
                onChange={(e) => setFormTitle(e.target.value)}
                placeholder={t('incidents.form.titlePlaceholder')}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="incident-severity">{t('incidents.form.severity')}</Label>
              <Select id="incident-severity" value={formSeverity} onChange={(e) => setFormSeverity(e.target.value)}>
                {SEVERITY_VALUES.map((v) => <option key={v} value={v}>{t(`alerts.severities.${v}`)}</option>)}
              </Select>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowCreateDialog(false)}>{t('common.cancel')}</Button>
            <Button onClick={handleCreate} disabled={!formTitle || createIncident.isPending}>
              {createIncident.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('incidents.createDialog.submit')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!showNoteDialog} onOpenChange={() => setShowNoteDialog(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('incidents.noteDialog.title')}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label htmlFor="note-title">{t('incidents.noteDialog.noteTitle')}</Label>
              <Input
                id="note-title"
                value={noteTitle}
                onChange={(e) => setNoteTitle(e.target.value)}
                placeholder={t('incidents.noteDialog.titlePlaceholder')}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="note-detail">{t('incidents.noteDialog.detail')}</Label>
              <Textarea
                id="note-detail"
                value={noteDetail}
                onChange={(e) => setNoteDetail(e.target.value)}
                placeholder={t('incidents.noteDialog.detailPlaceholder')}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowNoteDialog(null)}>{t('common.cancel')}</Button>
            <Button onClick={handleAddNote} disabled={!noteTitle || addTimeline.isPending}>
              {addTimeline.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('incidents.noteDialog.submit')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
