import { useState } from 'react';
import { Cable, Copy, RefreshCw, Trash2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import { formatRelativeTime } from '../lib/date';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import PermissionGate from '../components/PermissionGate';
import { useTunnels, useTunnelStatus, useCloseTunnel } from '../api/queries';
import { Button, buttonVariants } from '../components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { CodeView, Dot } from '../components/port/p2/parts';
import { cn } from '../lib/utils';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { usePermissions } from '../auth/usePermissions';

const STALE_HEARTBEAT_MS = 2 * 60 * 1000;
const CLI_SNIPPET = ['curl -fsSL https://railhook.io/install-cli.sh | bash', 'railhook login', 'railhook listen 3000'].join('\n');

export default function TunnelsPage() {
  const { t } = useTranslation();
  const { canManageEndpoints: canCloseTunnels } = usePermissions();
  const [closeId, setCloseId] = useState<string | null>(null);
  const tunnelsQuery = useTunnels();
  const statusQuery = useTunnelStatus();
  const closeTunnel = useCloseTunnel();
  const closing = closeTunnel.isPending;

  const loading = (tunnelsQuery.isLoading && !tunnelsQuery.data) || (statusQuery.isLoading && !statusQuery.data);
  const loadError = tunnelsQuery.error ?? statusQuery.error;
  const tunnels = tunnelsQuery.data ?? [];
  const status = statusQuery.data;
  const loadData = () => { tunnelsQuery.refetch(); statusQuery.refetch(); };

  const handleClose = async () => {
    if (!closeId) return;
    try {
      await closeTunnel.mutateAsync(closeId);
      showSuccess(t('tunnels.toast.closed'));
      setCloseId(null);
    } catch (err: any) {
      showApiError(err, 'tunnels.toast.closeFailed');
    }
  };

  const handleCopyUrl = (url: string) => {
    navigator.clipboard.writeText(url);
    showSuccess(t('common.copied'));
  };

  if (loading) {
    return (
      <PageSkeleton>
        <SkeletonRows count={3} height="h-24" />
      </PageSkeleton>
    );
  }

  return (
    <div className="mx-auto w-full max-w-[1280px] px-4 pb-16 pt-6 sm:px-6 lg:px-10 lg:pt-8">
      <PageHeader
        eyebrow={status ? t('tunnels.openCount', { count: status.activeTunnels }) : undefined}
        title={t('tunnels.title')}
        description={t('tunnels.subtitle')}
        actions={
          <Button variant="outline" onClick={loadData}>
            <RefreshCw className="h-4 w-4" aria-hidden /> {t('tunnels.refresh')}
          </Button>
        }
      />

      {status && (
        <p className="mb-6 text-[13px] text-muted-foreground">
          {t('tunnels.summary', { active: status.activeTunnels, pending: status.pendingRequests, mine: status.myTunnels.length })}
        </p>
      )}

      {loadError ? (
        <ErrorState error={loadError} fallbackKey="tunnels.toast.loadFailed" onRetry={loadData} />
      ) : tunnels.length === 0 ? (
        <EmptyState
          icon={Cable}
          title={t('tunnels.noTunnels')}
          description={t('tunnels.noTunnelsDesc')}
        />
      ) : (
        <Table className="animate-fade-in text-[13px]">
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="px-3">{t('tunnels.columns.url')}</TableHead>
              <TableHead className="w-36 px-3">{t('tunnels.localPort')}</TableHead>
              <TableHead className="w-36 px-3">{t('tunnels.lastHeartbeat')}</TableHead>
              <TableHead className="w-32 px-3 max-lg:hidden">{t('tunnels.created')}</TableHead>
              <TableHead className="w-40 px-3 max-xl:hidden">{t('tunnels.client')}</TableHead>
              <TableHead className="w-16 px-2"><span className="sr-only">{t('common.actions')}</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {tunnels.map((tunnel) => {
              const stale = !!tunnel.lastHeartbeat && Date.now() - new Date(tunnel.lastHeartbeat).getTime() > STALE_HEARTBEAT_MS;
              return (
                <TableRow key={tunnel.id} className={cn(stale && 'bg-retry-soft/40')}>
                  <TableCell className="max-w-[24rem] px-3">
                    <span className="flex min-w-0 items-center gap-2">
                      <Dot tone={stale ? 'retry' : 'ok'} />
                      <code className="min-w-0 truncate font-mono text-[12px]" title={tunnel.publicUrl}>{tunnel.publicUrl}</code>
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        onClick={() => handleCopyUrl(tunnel.publicUrl)}
                        title={t('tunnels.copyUrl')}
                        aria-label={t('tunnels.copyUrl')}
                        className="flex-shrink-0 text-muted-foreground hover:text-foreground"
                      >
                        <Copy className="h-3 w-3" />
                      </Button>
                    </span>
                    <span className="block pl-4 font-mono text-[11px] text-muted-foreground">{tunnel.publicSlug}</span>
                  </TableCell>
                  <TableCell className="px-3 font-mono text-[12px]">localhost:{tunnel.localPort}</TableCell>
                  <TableCell className={cn('px-3 text-muted-foreground', stale && 'text-retry')}>
                    {tunnel.lastHeartbeat ? formatRelativeTime(tunnel.lastHeartbeat) : '—'}
                  </TableCell>
                  <TableCell className="px-3 text-muted-foreground max-lg:hidden">{formatRelativeTime(tunnel.createdAt)}</TableCell>
                  <TableCell className="max-w-[10rem] truncate px-3 font-mono text-[12px] text-muted-foreground max-xl:hidden" title={tunnel.clientInfo ?? undefined}>{tunnel.clientInfo ?? '—'}</TableCell>
                  <TableCell className="px-2 text-right">
                    <PermissionGate allowed={canCloseTunnels} fallback="hide">
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        onClick={() => setCloseId(tunnel.id)}
                        title={t('tunnels.close')}
                        aria-label={t('tunnels.closeNamed', { name: tunnel.publicSlug })}
                        className="text-muted-foreground hover:text-halt"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </PermissionGate>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}

      <section className="mt-12 max-w-2xl">
        <h3 className="text-[15px] font-medium">{t('tunnels.startTitle')}</h3>
        <p className="mt-1 text-[13px] text-muted-foreground">{t('tunnels.startBody')}</p>
        <div className="mt-3">
          <CodeView value={CLI_SNIPPET} json={false} maxHeight="max-h-40" />
        </div>
      </section>

      <AlertDialog open={!!closeId} onOpenChange={(open) => !open && setCloseId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('tunnels.closeDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('tunnels.closeDialog.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={closing}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleClose}
              disabled={closing}
              className={buttonVariants({ variant: 'destructive' })}
            >
              {closing ? t('tunnels.closing') : t('tunnels.close')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
