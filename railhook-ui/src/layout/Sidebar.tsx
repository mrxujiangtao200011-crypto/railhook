import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, BookOpen, ChevronsLeft, LogOut, ShieldCheck, X } from 'lucide-react';
import { RailhookIcon } from '../components/icons/RailhookIcon';
import { Button } from '../components/ui/button';
import { cn } from '../lib/utils';
import { docsUrl } from '../lib/docsUrl';
import { hasMinRole, type Role } from '../auth/ProtectedRoute';
import ProjectSwitcher from '../components/ProjectSwitcher';
import OrganizationSwitcher from '../components/OrganizationSwitcher';
import { useDlqStats, useIncomingDlqStats, useOpenIncidentCount, useUnresolvedAlertCount } from '../api/queries';
import { PLATFORM_SECTION, PROJECT_SECTIONS, SETTINGS_SECTION, segmentOf, type NavEntry, type NavSection } from './nav.config';
import type { CurrentUserResponse } from '../types/api.types';

interface SidebarProps {
  projectId?: string;
  role: Role;
  user: CurrentUserResponse;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  isMobile?: boolean;
  onNavigate?: () => void;
  onLogout: () => void;
}

function Count({ value, alarm = false }: { value?: number; alarm?: boolean }) {
  if (!value) return null;
  return alarm ? (
    <span className="flex items-center gap-1.5 text-[12px] tabular-nums text-halt">
      <span className="h-1.5 w-1.5 rounded-full bg-halt" aria-hidden />
      {value}
    </span>
  ) : (
    <span className="text-[12px] tabular-nums text-muted-foreground">{value}</span>
  );
}

function NavLink({
  to, label, icon: Icon, active, narrow, nested = false, badge, onNavigate, external = false,
}: {
  to: string;
  label: string;
  icon?: React.ElementType;
  active: boolean;
  narrow: boolean;
  nested?: boolean;
  badge?: ReactNode;
  onNavigate?: () => void;
  external?: boolean;
}) {
  const className = cn(
    'relative flex items-center gap-3 px-3 text-[13px] transition-colors max-lg:min-h-11 max-lg:text-sm',
    nested ? 'h-8 pl-10' : 'h-9',
    narrow && 'justify-center px-0',
    active ? 'text-foreground' : 'text-muted-foreground hover:text-foreground',
  );
  const body = (
    <>
      {active && <span aria-hidden className="absolute bottom-2 left-0 top-2 w-[2px] bg-highlight" />}
      {Icon && <Icon className="h-4 w-4 flex-shrink-0" aria-hidden />}
      {!narrow && <span className="min-w-0 flex-1 truncate">{label}</span>}
      {!narrow && badge}
    </>
  );
  if (external) {
    return <a href={to} onClick={onNavigate} title={narrow ? label : undefined} className={className}>{body}</a>;
  }
  return (
    <Link to={to} onClick={onNavigate} aria-current={active ? 'page' : undefined} title={narrow ? label : undefined} className={className}>
      {body}
    </Link>
  );
}

export default function Sidebar({
  projectId, role, user, collapsed, onToggleCollapsed, isMobile = false, onNavigate, onLogout,
}: SidebarProps) {
  const { t, i18n } = useTranslation();
  const location = useLocation();
  const segment = segmentOf(location.pathname);
  const narrow = collapsed && !isMobile;
  const inPlatform = PLATFORM_SECTION.owns.includes(segment);
  const navigate = isMobile ? onNavigate : undefined;

  const { data: dlq } = useDlqStats(projectId);
  const { data: incomingDlq } = useIncomingDlqStats(projectId);
  const { data: alerts } = useUnresolvedAlertCount(projectId);
  const { data: incidents } = useOpenIncidentCount(projectId);

  const allowed = (entry: NavEntry) => !entry.requiredRole || hasMinRole(role, entry.requiredRole);

  const tabBadge = (tab: NavEntry): ReactNode => {
    if (tab.owns.includes('dlq')) return <Count value={dlq?.totalItems} alarm />;
    if (tab.owns.includes('incoming-dlq')) return <Count value={incomingDlq?.totalItems} alarm />;
    if (tab.owns.includes('alerts')) return <Count value={alerts?.count} alarm />;
    if (tab.owns.includes('incidents')) return <Count value={incidents?.count} alarm />;
    return null;
  };

  const sectionBadge = (section: NavSection, open: boolean): ReactNode => {
    if (open) return null;
    if (section.owns.includes('dlq')) return <Count value={(dlq?.totalItems ?? 0) + (incomingDlq?.totalItems ?? 0)} alarm />;
    if (section.owns.includes('alerts')) return <Count value={(alerts?.count ?? 0) + (incidents?.count ?? 0)} alarm />;
    return null;
  };

  const renderSection = (section: NavSection) => {
    if (!allowed(section)) return null;
    const open = section.owns.includes(segment);
    const tabs = section.tabs.filter(allowed);
    const nestedShown = open && tabs.length > 1 && !narrow;
    return (
      <div key={section.nameKey}>
        <NavLink
          to={section.path(projectId)}
          label={t(section.nameKey)}
          icon={section.icon}
          active={open && !nestedShown}
          narrow={narrow}
          badge={sectionBadge(section, open)}
          onNavigate={navigate}
        />
        {nestedShown && (
          <div className="pb-1.5">
            {tabs.map((tab) => (
              <NavLink
                key={tab.nameKey + tab.owns[0]}
                to={tab.path(projectId)}
                label={t(tab.nameKey)}
                active={tab.owns.includes(segment)}
                narrow={false}
                nested
                badge={tabBadge(tab)}
                onNavigate={navigate}
              />
            ))}
          </div>
        )}
      </div>
    );
  };

  const header = (
    <div className={cn('flex h-14 flex-shrink-0 items-center gap-2 px-4', narrow && 'justify-center px-2')}>
      <Link to="/" className="flex min-w-0 items-center gap-2 transition-opacity hover:opacity-70">
        <RailhookIcon className="h-5 w-5 flex-shrink-0" />
        {!narrow && <span className="text-[15px] font-medium tracking-[-0.02em]">Railhook</span>}
      </Link>
      {!narrow && inPlatform && (
        <span className="truncate text-[15px] text-muted-foreground">/ {t('nav.adminZone')}</span>
      )}
      {isMobile ? (
        <Button variant="ghost" size="icon-sm" onClick={onNavigate} className="ml-auto text-muted-foreground"
          title={t('common.close')} aria-label={t('common.close')}>
          <X className="h-4 w-4" />
        </Button>
      ) : (
        <Button variant="ghost" size="icon-sm" onClick={onToggleCollapsed}
          className={cn('ml-auto text-muted-foreground', narrow && 'ml-0 hidden')}
          title={t(collapsed ? 'nav.expandSidebar' : 'nav.collapseSidebar')}
          aria-label={t(collapsed ? 'nav.expandSidebar' : 'nav.collapseSidebar')}>
          <ChevronsLeft className={cn('h-4 w-4 transition-transform', collapsed && 'rotate-180')} />
        </Button>
      )}
    </div>
  );

  const account = (
    <div className="border-t border-rail px-2 py-2">
      <div className={cn('flex items-center gap-2.5 px-1 py-1', narrow && 'justify-center px-0')}>
        <span className="flex h-7 w-7 flex-shrink-0 items-center justify-center bg-foreground font-mono text-[11px] font-medium text-background">
          {user.user?.email?.charAt(0).toUpperCase() || 'U'}
        </span>
        {!narrow && (
          <>
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13px] leading-tight">{user.user?.email}</p>
              <OrganizationSwitcher />
            </div>
            <Button variant="ghost" size="icon-sm" onClick={onLogout}
              className="flex-shrink-0 text-muted-foreground hover:text-halt"
              title={t('nav.logout')} aria-label={t('nav.logout')}>
              <LogOut className="h-3.5 w-3.5" />
            </Button>
          </>
        )}
      </div>
      {narrow && (
        <Button variant="ghost" size="icon-sm" onClick={onToggleCollapsed} className="mx-auto mt-1 flex text-muted-foreground"
          title={t('nav.expandSidebar')} aria-label={t('nav.expandSidebar')}>
          <ChevronsLeft className="h-4 w-4 rotate-180" />
        </Button>
      )}
    </div>
  );

  if (inPlatform) {
    return (
      <div className="surface-ink flex h-full flex-col">
        {header}
        <nav aria-label={t('nav.platformAdmin')} className="flex-1 overflow-y-auto px-2 py-3">
          {PLATFORM_SECTION.tabs.map((tab) => (
            <NavLink
              key={tab.nameKey}
              to={tab.path()}
              label={t(tab.nameKey)}
              icon={tab.icon}
              active={tab.owns.includes(segment)}
              narrow={narrow}
              onNavigate={navigate}
            />
          ))}
        </nav>
        <div className="border-t border-rail px-2 py-2">
          <NavLink to="/admin/dashboard" label={t('nav.backToWorkspace')} icon={ArrowLeft} active={false} narrow={narrow} onNavigate={navigate} />
        </div>
        {account}
      </div>
    );
  }

  return (
    <div className="surface-ink flex h-full flex-col">
      {header}
      {!narrow && (
        <div className="border-y border-rail px-2 py-2">
          <ProjectSwitcher currentProjectId={projectId} />
        </div>
      )}
      {narrow && (
        <div className="flex justify-center border-y border-rail py-2">
          <ProjectSwitcher currentProjectId={projectId} collapsed />
        </div>
      )}
      <nav aria-label={t('nav.navigation')} className="flex-1 overflow-y-auto px-2 py-3">
        {PROJECT_SECTIONS.map(renderSection)}
      </nav>
      <div className="border-t border-rail px-2 py-2">
        {renderSection(SETTINGS_SECTION)}
        {/* A page load, not a route: the docs are their own site at /docs/. */}
        <NavLink to={docsUrl(i18n.language)} label={t('nav.documentation')} icon={BookOpen} active={false} narrow={narrow} onNavigate={navigate} external />
        {/* A courtesy, not the control: pages and the API refuse without platformAdmin. */}
        {user.platformAdmin && (
          <NavLink to={PLATFORM_SECTION.path()} label={t('nav.platformAdmin')} icon={ShieldCheck} active={false} narrow={narrow} onNavigate={navigate} />
        )}
      </div>
      {account}
    </div>
  );
}
