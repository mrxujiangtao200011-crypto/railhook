import { type LucideIcon, BookOpen, AlertTriangle, RefreshCw } from 'lucide-react';
import { type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { docsUrl } from '../lib/docsUrl';
import { resolveErrorMessage } from '../lib/toast';
import { Button } from './ui/button';
import { cn } from '../lib/utils';

interface EmptyStateProps {
  icon: LucideIcon;
  title: string;
  /** ReactNode: some descriptions name the record in <strong>. */
  description?: ReactNode;
  action?: ReactNode;
  docsLink?: string;
  className?: string;
}

export default function EmptyState({ icon: Icon, title, description, action, docsLink, className }: EmptyStateProps) {
  const { t, i18n } = useTranslation();
  return (
    <div className={cn('flex flex-col items-start border-t border-rail py-10', className)}>
      <h3 className="flex items-center gap-2 text-[15px] font-normal">
        <Icon className="h-4 w-4 flex-shrink-0 text-muted-foreground" aria-hidden />
        {title}
      </h3>
      {description && (
        <p className="mt-1 max-w-xl text-[13px] text-muted-foreground">{description}</p>
      )}
      {action && <div className="mt-4">{action}</div>}
      {docsLink && (
        <a href={docsUrl(i18n.language, docsLink)} className="mt-3 inline-flex min-h-[44px] items-center gap-1.5 text-xs text-muted-foreground transition-colors hover:text-foreground sm:min-h-0">
          <BookOpen className="h-3.5 w-3.5" />
          {t('common.learnMore')}
        </a>
      )}
    </div>
  );
}

interface ErrorStateProps {
  error?: unknown;
  fallbackKey?: string;
  description?: string;
  title?: string;
  onRetry?: () => void;
  retrying?: boolean;
  className?: string;
  testId?: string;
}

/** Never EmptyState for a failed request, or a down backend looks like an empty account. */
export function ErrorState({
  error,
  fallbackKey = 'common.error',
  description,
  title,
  onRetry,
  retrying = false,
  className,
  testId = 'error-state',
}: ErrorStateProps) {
  const { t } = useTranslation();
  const resolvedDescription = description ?? (error !== undefined ? resolveErrorMessage(error, fallbackKey) : t(fallbackKey));

  return (
    <div
      data-testid={testId}
      role="alert"
      className={className ?? 'flex flex-col items-start border-t border-rail py-10'}
    >
      <h3 className="flex items-center gap-2 text-[15px] font-normal text-halt">
        <AlertTriangle className="h-4 w-4 flex-shrink-0" aria-hidden />
        {title ?? t('common.loadErrorTitle')}
      </h3>
      <p className="mb-4 mt-1 max-w-xl text-[13px] text-muted-foreground">{resolvedDescription}</p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry} disabled={retrying}>
          <RefreshCw className={`h-3.5 w-3.5 ${retrying ? 'animate-spin' : ''}`} />
          {retrying ? t('common.retrying') : t('common.retry')}
        </Button>
      )}
    </div>
  );
}
