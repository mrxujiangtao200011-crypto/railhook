import { useMemo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, Copy } from 'lucide-react';
import { useCopyToClipboard } from '../../../hooks/useCopyToClipboard';
import { cn } from '../../../lib/utils';
import { Button } from '../../ui/button';

export function Section({ title, description, aside, children, className, id }: {
  title?: ReactNode;
  description?: ReactNode;
  aside?: ReactNode;
  children: ReactNode;
  className?: string;
  id?: string;
}) {
  return (
    <section id={id} className={cn('min-w-0', className)}>
      {(title || aside) && (
        <div className="mb-3 flex flex-wrap items-end justify-between gap-x-4 gap-y-2">
          <div className="min-w-0">
            {title && <h3 className="text-[15px] font-medium">{title}</h3>}
            {description && <p className="mt-0.5 max-w-2xl text-[13px] text-muted-foreground">{description}</p>}
          </div>
          {aside && <div className="flex flex-wrap items-center gap-2">{aside}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

export function FormSection({ title, description, children, footer, className }: {
  title: ReactNode;
  description?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn('grid gap-x-12 gap-y-4 border-t border-rail py-8 lg:grid-cols-[16rem_minmax(0,1fr)]', className)}>
      <div className="min-w-0">
        <h3 className="text-[15px] font-medium">{title}</h3>
        {description && <p className="mt-1 text-[13px] text-muted-foreground">{description}</p>}
      </div>
      <div className="min-w-0 max-w-2xl space-y-4">
        {children}
        {footer && <div className="flex flex-wrap items-center gap-2 pt-2">{footer}</div>}
      </div>
    </section>
  );
}

export function Ledger({ children, className }: { children: ReactNode; className?: string }) {
  return <dl className={cn('border-t border-rail', className)}>{children}</dl>;
}

export function LedgerRow({ label, children, tone }: { label: ReactNode; children: ReactNode; tone?: 'halt' | 'retry' }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-rail py-2.5 text-sm">
      <dt className="flex-shrink-0 text-muted-foreground">{label}</dt>
      <dd className={cn('min-w-0 break-words text-right tabular-nums', tone === 'halt' && 'text-halt', tone === 'retry' && 'text-retry')}>{children}</dd>
    </div>
  );
}

export function Fact({ label, children, mono = false }: { label: ReactNode; children: ReactNode; mono?: boolean }) {
  return (
    <div className="grid grid-cols-[9rem_minmax(0,1fr)] gap-4 border-b border-rail py-2.5 text-sm max-sm:grid-cols-1 max-sm:gap-0.5">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={cn('min-w-0 break-words', mono && 'font-mono text-[12px] leading-5')}>{children}</dd>
    </div>
  );
}

export function CopyButton({ value, label, className }: { value: string; label?: string; className?: string }) {
  const { t } = useTranslation();
  const { copied, copy } = useCopyToClipboard();
  return (
    <Button
      type="button"
      variant="ghost"
      size={label ? 'sm' : 'icon-sm'}
      className={cn('flex-shrink-0 text-muted-foreground', className)}
      title={t(copied ? 'common.copied' : 'common.copy')}
      aria-label={label ?? t(copied ? 'common.copied' : 'common.copy')}
      onClick={(e) => { e.stopPropagation(); void copy(value); }}
    >
      {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
      {label}
    </Button>
  );
}

export function CopyValue({ value, display, className }: { value: string; display?: ReactNode; className?: string }) {
  return (
    <span className={cn('flex min-w-0 items-center gap-1', className)}>
      <span className="min-w-0 flex-1 break-all font-mono text-[12px]">{display ?? value}</span>
      <CopyButton value={value} />
    </span>
  );
}

const TOKEN = /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|([{}[\],])/g;

function highlight(line: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  TOKEN.lastIndex = 0;
  while ((m = TOKEN.exec(line)) !== null) {
    if (m.index > last) out.push(line.slice(last, m.index));
    const [whole, str, colon, lit, num, punct] = m;
    const key = String(m.index);
    if (str !== undefined) {
      out.push(<span key={key} style={{ color: colon ? 'var(--code-key)' : 'var(--code-string)' }}>{str}</span>);
      if (colon) out.push(<span key={`${key}c`} style={{ color: 'var(--code-punct)' }}>{colon}</span>);
    } else if (lit !== undefined || num !== undefined) {
      out.push(<span key={key} style={{ color: 'var(--code-number)' }}>{whole}</span>);
    } else if (punct !== undefined) {
      out.push(<span key={key} style={{ color: 'var(--code-punct)' }}>{punct}</span>);
    }
    last = m.index + whole.length;
  }
  if (last < line.length) out.push(line.slice(last));
  return out;
}

export function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

export type LineMark = 'add' | 'remove' | 'change';

export function CodeView({ value, title, maxHeight = 'max-h-[32rem]', copy = true, marks, json = true, className }: {
  value: string;
  title?: ReactNode;
  maxHeight?: string;
  copy?: boolean;
  marks?: Record<number, LineMark>;
  json?: boolean;
  className?: string;
}) {
  const text = useMemo(() => (json ? prettyJson(value) : value), [value, json]);
  const lines = useMemo(() => text.split('\n'), [text]);
  return (
    <div className={cn('min-w-0 border border-rail', className)}>
      {(title || copy) && (
        <div className="flex h-9 items-center justify-between gap-3 border-b border-rail bg-secondary/60 pl-3 pr-1 text-[12px] text-muted-foreground">
          <span className="min-w-0 truncate">{title}</span>
          {copy && <CopyButton value={text} />}
        </div>
      )}
      <div className={cn('overflow-auto', maxHeight)}>
        <table className="w-full border-collapse font-mono text-[12px] leading-[1.6]" style={{ color: 'var(--code-fg)' }}>
          <tbody>
            {lines.map((line, i) => (
              <tr key={i} className={cn(marks?.[i] === 'add' && 'bg-ok-soft', marks?.[i] === 'remove' && 'bg-halt-soft', marks?.[i] === 'change' && 'bg-retry-soft')}>
                <td className="w-10 select-none pr-3 text-right align-top text-muted-foreground/70">{i + 1}</td>
                <td className="whitespace-pre pr-4">{json ? highlight(line) : line}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function Meter({ value, max, tone }: { value: number; max: number; tone?: 'halt' | 'retry' | 'ok' }) {
  const share = max > 0 ? Math.min(value / max, 1) : 0;
  return (
    <div className="h-[3px] w-full bg-secondary" aria-hidden>
      <div
        className={cn('h-full', tone === 'halt' ? 'bg-halt' : tone === 'retry' ? 'bg-retry' : tone === 'ok' ? 'bg-ok' : 'bg-foreground/60')}
        style={{ width: `${Math.max(share * 100, share > 0 ? 1.5 : 0)}%` }}
      />
    </div>
  );
}

export function Dot({ tone, className }: { tone: 'ok' | 'retry' | 'halt' | 'idle'; className?: string }) {
  return (
    <span
      aria-hidden
      className={cn(
        'inline-block h-2 w-2 flex-shrink-0 rounded-full',
        tone === 'ok' && 'bg-ok',
        tone === 'retry' && 'bg-retry',
        tone === 'halt' && 'bg-halt',
        tone === 'idle' && 'bg-idle',
        className,
      )}
    />
  );
}

export function Segmented<K extends string>({ items, value, onChange, label }: {
  items: { key: K; label: ReactNode; count?: number }[];
  value: K;
  onChange: (key: K) => void;
  label?: string;
}) {
  return (
    <div role="group" aria-label={label} className="-mx-1 flex max-w-full items-center overflow-x-auto">
      {items.map((item) => (
        <button
          key={item.key}
          type="button"
          aria-pressed={value === item.key}
          onClick={() => onChange(item.key)}
          className={cn(
            'flex h-9 flex-shrink-0 items-center gap-1.5 whitespace-nowrap px-2.5 text-[13px] transition-colors max-sm:h-11',
            value === item.key ? 'bg-secondary text-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {item.label}
          {item.count !== undefined && <span className="tabular-nums text-muted-foreground">{item.count}</span>}
        </button>
      ))}
    </div>
  );
}

export function PageBody({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('mx-auto w-full max-w-[1280px] px-4 pb-16 pt-6 sm:px-6 lg:px-10 lg:pt-8', className)}>{children}</div>;
}
