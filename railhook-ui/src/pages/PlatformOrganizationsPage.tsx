import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Building2 } from 'lucide-react';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import { SkeletonTable } from '../components/PageSkeleton';
import { Card } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import { usePlatformOrganizations } from '../api/queries';
import { formatDate } from '../lib/date';
import { cn } from '../lib/utils';
import { FilterBar } from './tableParts';
import {
  EmailText, EventsAgainstLimit, OrganizationLink, OrganizationStatusBadge, PLATFORM_TABLE, PLATFORM_TABLE_HEADER, PlatformErrorState,
  PlatformScope, useDebouncedValue,
} from './platformAdminParts';

export default function PlatformOrganizationsPage() {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [searchInput, setSearchInput] = useState('');
  const [suspendedOnly, setSuspendedOnly] = useState(false);
  const search = useDebouncedValue(searchInput.trim());

  const filters = useMemo(() => ({ search: search || undefined, suspendedOnly }), [search, suspendedOnly]);
  const { data, isLoading, isError, error, refetch, isRefetching } = usePlatformOrganizations(page, pageSize, filters);

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={data ? t('platformAdmin.organizations.count', { count: data.totalElements }) : t('platformAdmin.eyebrow')}
        description={t('platformAdmin.organizations.description')}
      />
      <PlatformScope />

      <FilterBar>
        <Input
          type="search"
          value={searchInput}
          onChange={(e) => { setSearchInput(e.target.value); setPage(0); }}
          placeholder={t('platformAdmin.organizations.search')}
          aria-label={t('platformAdmin.organizations.search')}
          className="h-9 w-full sm:w-72"
        />
        <div role="group" aria-label={t('platformAdmin.organizations.statusFilter')} className="flex">
          {(['all', 'suspended'] as const).map((key) => {
            const active = (key === 'suspended') === suspendedOnly;
            return (
              <button
                key={key}
                type="button"
                aria-pressed={active}
                onClick={() => { setSuspendedOnly(key === 'suspended'); setPage(0); }}
                className={cn('h-9 whitespace-nowrap px-3 text-[13px] transition-colors max-sm:h-11', active ? 'bg-secondary text-foreground' : 'text-muted-foreground hover:text-foreground')}
              >
                {t(key === 'all' ? 'platformAdmin.organizations.all' : 'platformAdmin.organizations.suspendedOnly')}
              </button>
            );
          })}
        </div>
      </FilterBar>

      {isError ? (
        <PlatformErrorState error={error} onRetry={() => refetch()} retrying={isRefetching} />
      ) : isLoading || !data ? (
        <Card className="overflow-hidden"><SkeletonTable rows={8} /></Card>
      ) : data.content.length === 0 ? (
        <EmptyState
          icon={Building2}
          title={t('platformAdmin.organizations.empty')}
          description={t('platformAdmin.organizations.emptyDesc')}
        />
      ) : (
        <Card className="overflow-hidden">
          <Table className={PLATFORM_TABLE}>
            <TableHeader className={PLATFORM_TABLE_HEADER}>
              <TableRow>
                <TableHead>{t('platformAdmin.columns.organization')}</TableHead>
                <TableHead>{t('platformAdmin.columns.plan')}</TableHead>
                <TableHead>{t('platformAdmin.columns.owner')}</TableHead>
                <TableHead className="text-right">{t('platformAdmin.columns.members')}</TableHead>
                <TableHead className="text-right">{t('platformAdmin.columns.projects')}</TableHead>
                <TableHead>{t('platformAdmin.columns.eventsThisMonth')}</TableHead>
                <TableHead>{t('platformAdmin.columns.status')}</TableHead>
                <TableHead className="text-right">{t('platformAdmin.columns.created')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.content.map((organization) => (
                <TableRow key={organization.id}>
                  <TableCell>
                    <OrganizationLink id={organization.id} name={organization.name} className="font-medium" />
                  </TableCell>
                  <TableCell className="text-[13px] text-muted-foreground">{organization.planName ?? '—'}</TableCell>
                  <TableCell className="text-muted-foreground">
                    <EmailText email={organization.ownerEmail} />
                  </TableCell>
                  <TableCell className="text-right text-[13px] tabular-nums">{organization.memberCount}</TableCell>
                  <TableCell className="text-right text-[13px] tabular-nums">{organization.projectCount}</TableCell>
                  <TableCell>
                    <EventsAgainstLimit current={organization.eventsThisMonth} limit={organization.eventsLimit} />
                  </TableCell>
                  <TableCell><OrganizationStatusBadge organization={organization} /></TableCell>
                  <TableCell className="whitespace-nowrap text-right font-mono text-xs text-muted-foreground">
                    {formatDate(organization.createdAt)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}

      {!isError && data && data.content.length > 0 && (
        <TablePagination
          page={page}
          pageSize={pageSize}
          totalElements={data.totalElements}
          totalPages={data.totalPages}
          onPageChange={setPage}
          onPageSizeChange={setPageSize}
        />
      )}
    </div>
  );
}
