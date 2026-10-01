import type { ReactNode } from 'react';
import { cn } from '../lib/utils';

/** Title is optional: on a tabbed page the header bar and tab already name the view. */
export default function PageHeader({
  eyebrow, title, description, actions, className,
}: {
  eyebrow?: ReactNode;
  title?: string;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('flex flex-wrap items-end justify-between gap-x-6 gap-y-4 pb-6', className)}>
      <div className="min-w-0">
        {eyebrow && <div className={cn('text-[13px] text-muted-foreground', title ? 'mb-1' : 'mb-0.5')}>{eyebrow}</div>}
        {title && <h2 className="break-words text-[22px] font-normal leading-tight tracking-[-0.015em]">{title}</h2>}
        {description && (
          <p className={cn('max-w-2xl text-[13px] text-muted-foreground', title && 'mt-1.5')}>{description}</p>
        )}
      </div>
      {actions && <div className="flex flex-shrink-0 flex-wrap items-center gap-2 max-sm:w-full">{actions}</div>}
    </div>
  );
}
