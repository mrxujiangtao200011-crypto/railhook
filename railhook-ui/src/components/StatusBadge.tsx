import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';

export type StatusKind = 'ok' | 'retry' | 'halt' | 'idle';

/** Backend InvoiceStatus is upper case; comparing to 'paid' once painted every paid invoice grey. */
export function kindOfInvoiceStatus(status: string): StatusKind {
  switch (status) {
    case 'PAID':
      return 'ok';
    case 'PAST_DUE':
      return 'halt';
    default:
      return 'idle';
  }
}

export function kindOfDeliveryStatus(status: string): StatusKind {
  switch (status) {
    case 'SUCCESS':
    case 'DELIVERED':
    case 'FORWARDED':
      return 'ok';
    case 'FAILED':
    case 'PROCESSING':
    case 'RETRYING':
      return 'retry';
    case 'DLQ':
    case 'ABANDONED':
      return 'halt';
    // Not ok (nothing arrived), not halt (nothing went wrong).
    case 'CANCELLED':
      return 'idle';
    default:
      return 'idle';
  }
}

export default function StatusBadge({
  kind, label, icon = true,
}: {
  kind: StatusKind;
  label: string;
  icon?: boolean;
}) {
  return (
    <span
      data-kind={kind}
      className={cn(
        'inline-flex items-center gap-2 whitespace-nowrap text-[13px]',
        kind === 'retry' && 'text-retry',
        kind === 'halt' && 'text-halt',
      )}
    >
      {icon && (
        <span
          aria-hidden
          className={cn(
            'h-2 w-2 flex-shrink-0 rounded-full',
            kind === 'ok' && 'bg-ok',
            kind === 'retry' && 'bg-retry',
            kind === 'halt' && 'bg-halt',
            kind === 'idle' && 'bg-idle',
          )}
        />
      )}
      {label}
    </span>
  );
}

/** autoDisabled reads halt, not idle: an owner whose endpoint was switched off for them must notice. */
export function EnabledBadge({
  enabled, autoDisabled = false,
}: {
  enabled: boolean;
  autoDisabled?: boolean;
}) {
  const { t } = useTranslation();
  if (!enabled && autoDisabled) return <StatusBadge kind="halt" label={t('endpoints.autoDisabled')} />;
  return <StatusBadge kind={enabled ? 'ok' : 'idle'} label={t(enabled ? 'common.enabled' : 'common.disabled')} />;
}
