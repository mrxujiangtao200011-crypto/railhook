import { useEffect, useMemo, useState, type ReactNode } from 'react';

export function useWide(query = '(min-width: 1280px)') {
  const [wide, setWide] = useState(() => typeof window !== 'undefined' && window.matchMedia(query).matches);
  useEffect(() => {
    const m = window.matchMedia(query);
    const on = () => setWide(m.matches);
    m.addEventListener('change', on);
    return () => m.removeEventListener('change', on);
  }, [query]);
  return wide;
}

export function Tabs<T extends string>({ value, onChange, items, label }: {
  value: T;
  onChange: (v: T) => void;
  items: { value: T; label: string; count?: number }[];
  label: string;
}) {
  return (
    <div role="tablist" aria-label={label} className="flex gap-6 overflow-x-auto border-b border-rail">
      {items.map((item) => (
        <button
          key={item.value}
          role="tab"
          type="button"
          aria-selected={value === item.value}
          onClick={() => onChange(item.value)}
          className={cn(
            'relative flex h-10 flex-shrink-0 items-center gap-1.5 text-[13px] transition-colors max-sm:h-11',
            value === item.value ? 'text-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {item.label}
          {item.count ? <span className="tabular-nums text-muted-foreground">{item.count}</span> : null}
          {value === item.value && <span aria-hidden className="absolute inset-x-0 bottom-0 h-[2px] bg-foreground" />}
        </button>
      ))}
    </div>
  );
}
import { useTranslation } from 'react-i18next';
import { Check, Copy } from 'lucide-react';
import { useCopyToClipboard } from '../../../hooks/useCopyToClipboard';
import { formatJson } from '../../../lib/json';
import { cn } from '../../../lib/utils';

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

export function JsonView({ value, title, maxHeight = 'max-h-[32rem]', className }: {
  value: string;
  title?: ReactNode;
  maxHeight?: string;
  className?: string;
}) {
  const { t } = useTranslation();
  const { copied, copy } = useCopyToClipboard();
  const text = useMemo(() => formatJson(value), [value]);
  const lines = useMemo(() => text.split('\n'), [text]);
  return (
    <div className={cn('min-w-0 border border-rail', className)}>
      <div className="flex h-9 items-center justify-between gap-3 border-b border-rail bg-secondary/60 pl-3 pr-1 text-[12px] text-muted-foreground">
        <span className="min-w-0 truncate">{title}</span>
        <button
          type="button"
          onClick={() => copy(text)}
          className="flex h-8 items-center gap-1.5 px-2 text-[12px] text-muted-foreground hover:text-foreground max-sm:h-10"
          title={copied ? t('common.copied') : undefined}
        >
          {copied ? <Check className="h-3.5 w-3.5 text-ok" aria-hidden /> : <Copy className="h-3.5 w-3.5" aria-hidden />}
          {t('common.copy')}
        </button>
      </div>
      <div className={cn('overflow-auto', maxHeight)}>
        <table className="w-full border-collapse font-mono text-[12px] leading-[1.6]" style={{ color: 'var(--code-fg)' }}>
          <tbody>
            {lines.map((line, i) => (
              <tr key={i}>
                <td className="w-10 select-none pr-3 text-right align-top text-muted-foreground/70">{i + 1}</td>
                <td className="whitespace-pre pr-4">{highlight(line)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function SegmentBar({ parts, className }: { parts: { value: number; className: string }[]; className?: string }) {
  const total = parts.reduce((s, p) => s + p.value, 0);
  if (total === 0) return <span className={cn('block h-1.5 w-full bg-secondary', className)} aria-hidden />;
  return (
    <span className={cn('flex h-1.5 w-full gap-px', className)} aria-hidden>
      {parts.filter((p) => p.value > 0).map((p, i) => (
        <span key={i} className={p.className} style={{ width: `${(p.value / total) * 100}%` }} />
      ))}
    </span>
  );
}

export function Ledger({ children, className }: { children: ReactNode; className?: string }) {
  return <dl className={cn('border-t border-rail', className)}>{children}</dl>;
}

export function LedgerRow({ label, children, tone }: { label: ReactNode; children: ReactNode; tone?: 'halt' | 'retry' }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-rail py-2.5 text-sm">
      <dt className="min-w-0 text-muted-foreground">{label}</dt>
      <dd className={cn('min-w-0 text-right tabular-nums', tone === 'halt' && 'text-halt', tone === 'retry' && 'text-retry')}>{children}</dd>
    </div>
  );
}

export function Section({ title, aside, children, className }: { title?: ReactNode; aside?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cn('min-w-0', className)}>
      {(title || aside) && (
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          {title && <h3 className="text-[15px] font-medium">{title}</h3>}
          {aside}
        </div>
      )}
      {children}
    </section>
  );
}

export function Segmented<T extends string>({ value, onChange, options, label }: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: string; count?: ReactNode }[];
  label: string;
}) {
  return (
    <div role="group" aria-label={label} className="-mx-1 flex max-w-full items-center overflow-x-auto">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}
          className={cn(
            'flex h-9 flex-shrink-0 items-center gap-1.5 whitespace-nowrap px-2.5 text-[13px] transition-colors max-sm:h-11',
            value === o.value ? 'bg-secondary text-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {o.label}
          {o.count !== undefined && <span className="tabular-nums text-muted-foreground">{o.count}</span>}
        </button>
      ))}
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
