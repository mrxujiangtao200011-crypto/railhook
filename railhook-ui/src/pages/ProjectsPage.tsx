import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus, FolderKanban, Trash2, Copy } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess, showCriticalSuccess } from '../lib/toast';
import { useProjects, useDeleteProject } from '../api/queries';
import { dashboardApi, type DashboardStats } from '../api/dashboard.api';
import { formatDate, formatRelativeTime } from '../lib/date';
import PageSkeleton, { SkeletonTable } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import StatusBadge, { type StatusKind } from '../components/StatusBadge';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { Button } from '../components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { cn } from '../lib/utils';
import { PageBody } from '../components/port/p2/parts';
import CreateProjectDialog from '../components/CreateProjectDialog';
import DangerConfirmDialog from '../components/DangerConfirmDialog';

function kindOfSuccessRate(rate: number): StatusKind {
  if (rate >= 95) return 'ok';
  if (rate >= 80) return 'retry';
  return 'halt';
}

export default function ProjectsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { data: projects = [], isLoading, isError, error, refetch, isRefetching } = useProjects();
  const deleteProject = useDeleteProject();
  const { canCreateProject, canDeleteProject } = usePermissions();

  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [healthStats, setHealthStats] = useState<Record<string, DashboardStats>>({});

  const deleting = deleteProject.isPending;

  useEffect(() => {
    projects.forEach((project) => {
      if (!healthStats[project.id]) {
        dashboardApi.getProjectStats(project.id)
          .then((stats) => setHealthStats((prev) => ({ ...prev, [project.id]: stats })))
          .catch(() => { /* health is best-effort — a project card without it still works */ });
      }
    });
  }, [projects]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleDelete = () => {
    if (!deleteId) return;
    deleteProject.mutate(deleteId, {
      onSuccess: () => {
        showCriticalSuccess(t('projects.toast.deleted'));
        setDeleteId(null);
      },
      onError: (err: any) => showApiError(err, 'projects.toast.deleteFailed'),
    });
  };

  const handleCopyId = (id: string) => {
    navigator.clipboard.writeText(id);
    showSuccess(t('projects.toast.idCopied'));
  };

  if (isLoading) {
    return (
      <PageSkeleton>
        <SkeletonTable rows={4} />
      </PageSkeleton>
    );
  }

  const newProjectButton = (
    <PermissionGate allowed={canCreateProject}>
      <VerificationGate>
        <Button onClick={() => setShowCreateDialog(true)}>
          <Plus className="h-4 w-4" aria-hidden />
          {t('projects.newProject')}
        </Button>
      </VerificationGate>
    </PermissionGate>
  );

  return (
    <PageBody>
      <PageHeader
        eyebrow={projects.length > 0 ? t('projects.count', { count: projects.length }) : undefined}
        title={t('projects.title')}
        description={t('projects.subtitle')}
        actions={newProjectButton}
      />

      {isError ? (
        <ErrorState error={error} fallbackKey="projects.loadFailed" onRetry={() => refetch()} retrying={isRefetching} />
      ) : projects.length === 0 ? (
        <EmptyState
          icon={FolderKanban}
          title={t('projects.noProjects')}
          description={canCreateProject ? t('projects.noProjectsDesc') : t('projects.noProjectsViewer')}
          action={
            <PermissionGate allowed={canCreateProject}>
              <VerificationGate>
                <Button onClick={() => setShowCreateDialog(true)}>
                  <Plus className="h-4 w-4" aria-hidden />
                  {t('projects.createFirst')}
                </Button>
              </VerificationGate>
            </PermissionGate>
          }
          docsLink="start/quickstart"
        />
      ) : (
        <Table className="text-[13px]">
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="px-3">{t('projects.columns.project')}</TableHead>
              <TableHead className="w-36 px-3">{t('projects.columns.delivered')}</TableHead>
              <TableHead className="w-28 px-3 text-right">{t('projects.columns.endpoints')}</TableHead>
              <TableHead className="w-40 px-3">{t('projects.columns.lastEvent')}</TableHead>
              <TableHead className="w-32 px-3 max-lg:hidden">{t('projects.columns.created')}</TableHead>
              <TableHead className="w-24 px-3"><span className="sr-only">{t('common.actions')}</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {projects.map((project) => {
              const stats = healthStats[project.id];
              const ds = stats?.deliveryStats;
              const lastEvent = stats?.recentEvents?.[0];
              const endpointCount = stats?.endpointHealth?.length ?? 0;
              const kind = ds && ds.totalDeliveries > 0 ? kindOfSuccessRate(ds.successRate) : 'idle';
              return (
                <TableRow key={project.id} className={cn('group/row', kind === 'halt' && 'bg-halt-soft/40')}>
                  <TableCell className="px-3 py-3" data-cell="wide">
                    <button
                      onClick={() => navigate(`/admin/projects/${project.id}/endpoints`)}
                      className="block min-w-0 max-w-full text-left underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    >
                      <span className="block truncate text-[14px]">{project.name}</span>
                    </button>
                    {project.description && <p className="mt-0.5 line-clamp-1 text-[12px] text-muted-foreground">{project.description}</p>}
                    <nav aria-label={project.name} className="mt-1 flex flex-wrap gap-x-3 text-[12px] text-muted-foreground">
                      {[
                        { label: t('projects.quickLinks.endpoints'), path: `/admin/projects/${project.id}/endpoints` },
                        { label: t('projects.quickLinks.events'), path: `/admin/projects/${project.id}/events` },
                        { label: t('projects.quickLinks.deliveries'), path: `/admin/projects/${project.id}/deliveries` },
                        { label: t('projects.quickLinks.keys'), path: `/admin/projects/${project.id}/api-keys` },
                      ].map((link) => (
                        <Link key={link.label} to={link.path} className="hover:text-foreground max-sm:min-h-[44px] max-sm:py-2">{link.label}</Link>
                      ))}
                    </nav>
                  </TableCell>
                  <TableCell className="px-3">
                    {ds && ds.totalDeliveries > 0
                      ? <StatusBadge kind={kind} label={t('projects.health.successRate', { rate: Math.round(ds.successRate) })} />
                      : <span className="text-muted-foreground">{t('projects.health.noDeliveries')}</span>}
                  </TableCell>
                  <TableCell className="px-3 text-right tabular-nums">
                    {stats ? (endpointCount > 0 ? endpointCount : <span className="text-muted-foreground">{t('projects.health.noEndpoints')}</span>) : '—'}
                  </TableCell>
                  <TableCell className="px-3 text-muted-foreground">
                    {lastEvent ? formatRelativeTime(lastEvent.createdAt) : stats ? t('projects.health.noEvents') : '—'}
                  </TableCell>
                  <TableCell className="px-3 font-mono text-[12px] text-muted-foreground max-lg:hidden">{formatDate(project.createdAt)}</TableCell>
                  <TableCell className="px-2 text-right">
                    <div className="flex justify-end gap-0.5">
                      <Button
                        variant="ghost" size="icon-sm"
                        onClick={() => handleCopyId(project.id)}
                        title={t('common.copyId')} aria-label={t('projects.copyIdOf', { name: project.name })}
                        className="text-muted-foreground"
                      >
                        <Copy className="h-3.5 w-3.5" />
                      </Button>
                      {canDeleteProject && (
                        <Button
                          variant="ghost" size="icon-sm"
                          onClick={() => setDeleteId(project.id)}
                          title={t('common.delete')} aria-label={t('projects.deleteNamed', { name: project.name })}
                          className="text-muted-foreground hover:text-halt"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}

      <CreateProjectDialog
        open={showCreateDialog}
        onOpenChange={setShowCreateDialog}
        onCreated={(project) => navigate(`/admin/projects/${project.id}/connection-setup`)}
      />

      <DangerConfirmDialog
        open={!!deleteId}
        onOpenChange={(open) => !open && setDeleteId(null)}
        title={t('projects.deleteDialog.title', { name: projects.find((p) => p.id === deleteId)?.name ?? '' })}
        description={t('projects.deleteDialog.description')}
        confirmName={projects.find((p) => p.id === deleteId)?.name || ''}
        impact={[
          t('projects.deleteDialog.impactEndpoints'),
          t('projects.deleteDialog.impactEvents'),
          t('projects.deleteDialog.impactKeys'),
        ]}
        onConfirm={handleDelete}
        loading={deleting}
        confirmLabel={t('projects.deleteDialog.confirm')}
      />
    </PageBody>
  );
}
