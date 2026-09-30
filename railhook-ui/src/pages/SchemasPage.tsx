import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { FileJson2 } from 'lucide-react';
import PageHeader from '../components/PageHeader';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { useEventTypes } from '../api/queries';
import type { EventTypeCatalogResponse } from '../api/schemas.api';
import SchemaValidationPanel from './schemas/SchemaValidationPanel';
import SchemaListPanel from './schemas/SchemaListPanel';
import SchemaVersionHistory, { RecentSchemaChanges } from './schemas/SchemaVersionHistory';

export default function SchemasPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const [selected, setSelected] = useState<EventTypeCatalogResponse | null>(null);
  const { data: eventTypes = [], isLoading, isError, error, refetch, isFetching } = useEventTypes(projectId);

  if (!projectId) return null;

  if (isLoading) {
    return (
      <PageSkeleton>
        <SkeletonCards count={1} height="h-36" cols="grid-cols-1" />
        <SkeletonCards count={2} height="h-80" cols="grid-cols-1 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]" />
      </PageSkeleton>
    );
  }

  // Otherwise a failed request draws "0 event types": a down backend posing as an empty project.
  if (isError) {
    return (
      <div className="mx-auto w-full max-w-[1280px] px-4 pb-16 pt-6 sm:px-6 lg:px-10 lg:pt-8">
        <PageHeader title={t('schemas.title')} description={t('schemas.subtitle')} />
        <ErrorState
          error={error}
          fallbackKey="schemas.loadFailed"
          onRetry={() => refetch()}
          retrying={isFetching}
        />
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-[1280px] px-4 pb-16 pt-6 sm:px-6 lg:px-10 lg:pt-8">
      <PageHeader
        eyebrow={t('schemas.typeCount', { count: eventTypes.length })}
        title={t('schemas.title')}
        description={t('schemas.subtitle')}
      />

      <div className="space-y-8">
        <SchemaValidationPanel projectId={projectId} />

        <div className="grid items-start gap-x-10 gap-y-8 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]">
          <SchemaListPanel projectId={projectId} selected={selected} onSelect={setSelected} />

          <div className="min-w-0 space-y-4">
            {selected ? (
              <SchemaVersionHistory
                projectId={projectId}
                eventType={selected}
                onDeleted={() => setSelected(null)}
              />
            ) : (
              <>
                <RecentSchemaChanges projectId={projectId} />
                <EmptyState
                  icon={FileJson2}
                  title={t('schemas.selectEventType')}
                  description={t('schemas.selectEventTypeHint')}
                />
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
