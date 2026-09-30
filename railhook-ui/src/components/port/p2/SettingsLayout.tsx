import type { ReactNode } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useProjects } from '../../../api/queries';
import { hasMinRole } from '../../../auth/ProtectedRoute';
import { usePermissions } from '../../../auth/usePermissions';
import { SETTINGS_SECTION, segmentOf } from '../../../layout/nav.config';
import { projectToOpen } from '../../../lib/lastProject';
import { cn } from '../../../lib/utils';

export default function SettingsLayout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const location = useLocation();
  const { projectId: routeProjectId } = useParams<{ projectId: string }>();
  const { data: projects = [] } = useProjects();
  const { role } = usePermissions();
  const projectId = routeProjectId ?? projectToOpen(projects);
  const segment = segmentOf(location.pathname);
  const tabs = SETTINGS_SECTION.tabs.filter((tab) => !tab.requiredRole || hasMinRole(role, tab.requiredRole));

  return (
    <div className="mx-auto w-full max-w-[1280px] px-4 pb-16 pt-6 sm:px-6 lg:grid lg:grid-cols-[12rem_minmax(0,1fr)] lg:gap-10 lg:px-10 lg:pt-8">
      <nav aria-label={t('nav.settings')} className="-mx-4 mb-6 overflow-x-auto border-b border-rail px-4 sm:-mx-6 sm:px-6 lg:mx-0 lg:mb-0 lg:border-b-0 lg:px-0">
        <ul className="flex gap-5 lg:sticky lg:top-6 lg:flex-col lg:gap-0">
          {tabs.map((tab) => {
            const active = tab.owns.includes(segment);
            return (
              <li key={tab.nameKey} className="flex-shrink-0">
                <Link
                  to={tab.path(projectId)}
                  aria-current={active ? 'page' : undefined}
                  className={cn(
                    'relative flex h-11 items-center whitespace-nowrap text-[13px] transition-colors lg:h-9 lg:pl-3',
                    active ? 'text-foreground' : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  {active && <span aria-hidden className="absolute inset-x-0 bottom-0 h-[2px] bg-foreground lg:inset-y-2 lg:left-0 lg:right-auto lg:h-auto lg:w-[2px]" />}
                  {t(tab.nameKey)}
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
      <div className="min-w-0">{children}</div>
    </div>
  );
}
