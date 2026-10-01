import { Fragment, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  Bell, Check, ChevronDown, Clock, Loader2, Mail, MessageSquare, Pencil, Plus, Search, Siren,
  Trash2, VolumeX, Webhook,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showSuccess, showApiError } from '../lib/toast';
import {
  useAlertRules, useCreateAlertRule, useDeleteAlertRule, useUpdateAlertRule,
  useAlertEvents, useResolveAlert, useResolveAllAlerts, useUnresolvedAlertCount,
  useAlertChannels, useAlertConditions, useEndpoints,
} from '../api/queries';
import type {
  AlertRuleRequest, AlertRuleResponse, AlertSeverity, ConfigProperty, ConfigSchema,
} from '../types/api.types';
import ConfigSchemaFields, { isFilled } from '../components/ConfigSchemaFields';
import { formatDateTime, formatRelativeFuture, formatRelativeTime } from '../lib/date';
import PageSkeleton, { SkeletonCards } from '../components/PageSkeleton';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import { Button, buttonVariants } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';
import { Switch } from '../components/ui/switch';
import { Badge } from '../components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table';
import { TablePagination } from '../components/ui/table-pagination';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';
import { cn } from '../lib/utils';
import { STATUS_FILL, STATUS_TEXT, kindOfSeverity } from '../components/charts';

const SEVERITY_VALUES: AlertSeverity[] = ['INFO', 'WARNING', 'CRITICAL'];
const SNOOZE_HOURS = [1, 4, 8, 24];
const NO_SCHEMA: ConfigSchema = { type: 'object', properties: {}, required: [] };

const CHANNEL_ICON: Record<string, React.ElementType> = {
  IN_APP: Bell,
  EMAIL: Mail,
  WEBHOOK: Webhook,
  SLACK: MessageSquare,
  PAGERDUTY: Siren,
  OPSGENIE: Siren,
};

function defaults(schema: ConfigSchema, keep: Record<string, string> = {}): Record<string, string> {
  const values: Record<string, string> = {};
  for (const [name, property] of Object.entries(schema.properties)) {
    if (property.writeOnly) continue;
    values[name] = keep[name] ?? (property.default != null ? String(property.default) : '');
  }
  return values;
}

function isArmed(rule: { enabled: boolean; muted: boolean; snoozedUntil: string | null }): boolean {
  if (!rule.enabled || rule.muted) return false;
  return !rule.snoozedUntil || new Date(rule.snoozedUntil) <= new Date();
}

export default function AlertsPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManageEndpoints } = usePermissions();

  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [deleteRuleId, setDeleteRuleId] = useState<string | null>(null);
  const [eventsPage, setEventsPage] = useState(0);
  const [eventsPageSize, setEventsPageSize] = useState(20);
  const [expandedEventId, setExpandedEventId] = useState<string | null>(null);
  const [snoozeDropdownId, setSnoozeDropdownId] = useState<string | null>(null);

  const [editingRule, setEditingRule] = useState<AlertRuleResponse | null>(null);
  const [formName, setFormName] = useState('');
  const [formType, setFormType] = useState('');
  const [formSeverity, setFormSeverity] = useState<AlertSeverity>('WARNING');
  const [formConditionValues, setFormConditionValues] = useState<Record<string, string>>({});
  const [formDescription, setFormDescription] = useState('');
  const [formChannel, setFormChannel] = useState('IN_APP');
  const [formConfig, setFormConfig] = useState<Record<string, string>>({});

  const {
    data: rules = [], isLoading: rulesLoading, isError: rulesIsError, error: rulesError, refetch: refetchRules,
  } = useAlertRules(projectId);
  const {
    data: eventsData, isLoading: eventsLoading, isError: eventsIsError, error: eventsError, refetch: refetchEvents,
  } = useAlertEvents(projectId, eventsPage, eventsPageSize);
  const { data: unresolvedData } = useUnresolvedAlertCount(projectId);
  const { data: channels = [] } = useAlertChannels();
  const { data: conditions = [] } = useAlertConditions();
  const { data: endpoints = [] } = useEndpoints(projectId);

  const createRule = useCreateAlertRule(projectId!);
  const deleteRule = useDeleteAlertRule(projectId!);
  const updateRule = useUpdateAlertRule(projectId!);
  const resolveAlert = useResolveAlert(projectId!);
  const resolveAll = useResolveAllAlerts(projectId!);

  const unresolvedCount = unresolvedData?.count ?? 0;
  const events = eventsData?.content ?? [];
  const armedCount = rules.filter(isArmed).length;
  const silencedCount = rules.length - armedCount;
  const firingByRule = new Map(events.filter((e) => !e.resolved).map((e) => [e.alertRuleId, e] as const));
  const ruleState = (rule: (typeof rules)[number], firing: unknown): 'firing' | 'armed' | 'muted' | 'snoozed' | 'off' => {
    if (!rule.enabled) return 'off';
    if (firing) return 'firing';
    if (rule.muted) return 'muted';
    if (rule.snoozedUntil && new Date(rule.snoozedUntil) > new Date()) return 'snoozed';
    return 'armed';
  };
  const STATE_ORDER = { firing: 0, armed: 1, snoozed: 2, muted: 3, off: 4 } as const;
  const sortedRules = [...rules].sort((a, b) => STATE_ORDER[ruleState(a, firingByRule.get(a.id))] - STATE_ORDER[ruleState(b, firingByRule.get(b.id))]);

  const conditionOf = (id: string) => conditions.find((c) => c.id === id);
  const channelOf = (id: string) => channels.find((c) => c.id === id);
  const channelLabel = (id: string) => t(`alerts.channels.${id}`, { defaultValue: channelOf(id)?.displayName ?? id });
  const conditionLabel = (id: string) => t(`alerts.types.${id}.label`, { defaultValue: conditionOf(id)?.displayName ?? id });
  const conditionSchema = conditionOf(formType)?.configSchema ?? NO_SCHEMA;
  const channelSchema = channelOf(formChannel)?.configSchema ?? NO_SCHEMA;
  const storedSecrets = editingRule && editingRule.channel === formChannel ? editingRule.configuredSecrets : [];
  const endpointOptions = endpoints.map((e) => ({ id: e.id, label: e.description || e.url }));

  const openCreate = () => {
    const type = conditions[0]?.id ?? '';
    setEditingRule(null);
    setFormName('');
    setFormType(type);
    setFormSeverity('WARNING');
    setFormConditionValues(defaults(conditionOf(type)?.configSchema ?? NO_SCHEMA));
    setFormDescription('');
    setFormChannel('IN_APP');
    setFormConfig({});
    setShowCreateDialog(true);
  };

  const openEdit = (rule: AlertRuleResponse) => {
    setEditingRule(rule);
    setFormName(rule.name);
    setFormType(rule.alertType);
    setFormSeverity(rule.severity);
    setFormConditionValues({
      thresholdValue: rule.thresholdValue != null ? String(rule.thresholdValue) : '',
      windowMinutes: String(rule.windowMinutes),
      endpointId: rule.endpointId ?? '',
    });
    setFormDescription(rule.description ?? '');
    setFormChannel(rule.channel);
    setFormConfig({ ...rule.channelConfig });
    setShowCreateDialog(true);
  };

  const changeType = (type: string) => {
    setFormType(type);
    setFormConditionValues((current) => defaults(conditionOf(type)?.configSchema ?? NO_SCHEMA, current));
  };

  const changeChannel = (channel: string) => {
    setFormChannel(channel);
    const schema = channelOf(channel)?.configSchema ?? NO_SCHEMA;
    setFormConfig(editingRule?.channel === channel ? { ...editingRule.channelConfig } : defaults(schema));
  };

  const fieldLabel = (scope: string, owner: string) => (name: string, property: ConfigProperty) =>
    t(`alerts.${scope}.${owner}.${name}`, { defaultValue: property.title });
  const fieldHint = (scope: string, owner: string) => (name: string, property: ConfigProperty) =>
    t(`alerts.${scope}.${owner}.${name}Hint`, { defaultValue: property.description ?? '' }) || undefined;

  const handleSubmit = async () => {
    const reads = (name: string) => name in conditionSchema.properties && (formConditionValues[name] ?? '') !== '';
    const channelConfig: Record<string, string> = {};
    for (const [name, property] of Object.entries(channelSchema.properties)) {
      const value = formConfig[name] ?? '';
      // A blank secret keeps the stored one; a blank plain setting clears it.
      if (property.writeOnly && value.trim() === '') continue;
      channelConfig[name] = value;
    }
    const data: AlertRuleRequest = {
      name: formName,
      alertType: formType,
      severity: formSeverity,
      thresholdValue: reads('thresholdValue') ? parseFloat(formConditionValues.thresholdValue) : undefined,
      windowMinutes: reads('windowMinutes') ? parseInt(formConditionValues.windowMinutes) : undefined,
      endpointId: reads('endpointId') ? formConditionValues.endpointId : undefined,
      description: editingRule ? formDescription : formDescription || undefined,
      channel: formChannel,
      channelConfig,
    };
    try {
      if (editingRule) {
        await updateRule.mutateAsync({ ruleId: editingRule.id, data });
        showSuccess(t('alerts.toast.ruleUpdated'));
      } else {
        await createRule.mutateAsync(data);
        showSuccess(t('alerts.toast.ruleCreated'));
      }
      setShowCreateDialog(false);
    } catch (err: any) {
      showApiError(err, editingRule ? 'alerts.toast.updateFailed' : 'alerts.toast.createFailed');
    }
  };

  const handleDelete = async () => {
    if (!deleteRuleId) return;
    try {
      await deleteRule.mutateAsync(deleteRuleId);
      showSuccess(t('alerts.toast.ruleDeleted'));
    } catch (err: any) {
      showApiError(err, 'alerts.toast.deleteFailed');
    } finally {
      setDeleteRuleId(null);
    }
  };

  const handleToggleRule = async (ruleId: string, enabled: boolean) => {
    try {
      await updateRule.mutateAsync({ ruleId, data: { enabled } });
    } catch (err: any) {
      showApiError(err, 'alerts.toast.updateFailed');
    }
  };

  const handleMuteRule = async (ruleId: string, muted: boolean) => {
    try {
      await updateRule.mutateAsync({ ruleId, data: { muted } });
      showSuccess(muted ? t('alerts.toast.muted') : t('alerts.toast.unmuted'));
    } catch (err: any) {
      showApiError(err, 'alerts.toast.updateFailed');
    }
  };

  const handleSnoozeRule = async (ruleId: string, hours: number) => {
    const snoozedUntil = new Date(Date.now() + hours * 3600000).toISOString();
    try {
      await updateRule.mutateAsync({ ruleId, data: { snoozedUntil } });
      showSuccess(t('alerts.toast.snoozed', { hours }));
    } catch (err: any) {
      showApiError(err, 'alerts.toast.updateFailed');
    }
  };

  const handleResolve = async (eventId: string) => {
    try {
      await resolveAlert.mutateAsync(eventId);
    } catch (err: any) {
      showApiError(err, 'alerts.toast.resolveFailed');
    }
  };

  const handleResolveAll = async () => {
    try {
      const result = await resolveAll.mutateAsync();
      showSuccess(t('alerts.toast.allResolved', { count: result.resolved }));
    } catch (err: any) {
      showApiError(err, 'alerts.toast.resolveFailed');
    }
  };

  if (rulesLoading && eventsLoading) {
    return (
      <PageSkeleton maxWidth="max-w-none">
        <SkeletonCards count={3} height="h-[104px]" cols="grid-cols-1 lg:grid-cols-3" />
        <SkeletonCards count={1} height="h-[320px]" cols="grid-cols-1" />
      </PageSkeleton>
    );
  }

  return (
    <div className="p-4 lg:p-8">
      <PageHeader
        title={t('alerts.title')}
        description={t('alerts.subtitle')}
        actions={
          <>
            {unresolvedCount > 0 && (
              <PermissionGate allowed={canManageEndpoints}>
                <VerificationGate>
                  <Button variant="outline" size="sm" onClick={handleResolveAll} disabled={resolveAll.isPending}>
                    <Check className="h-4 w-4" /> {t('alerts.resolveAll')}
                  </Button>
                </VerificationGate>
              </PermissionGate>
            )}
            <PermissionGate allowed={canManageEndpoints}>
              <VerificationGate>
                <Button onClick={openCreate}>
                  <Plus className="h-4 w-4" /> {t('alerts.createRule')}
                </Button>
              </VerificationGate>
            </PermissionGate>
          </>
        }
      />

      <div className="space-y-10">
        <p className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
          <span className="flex items-center gap-2">
            <span aria-hidden className={cn('h-2 w-2 rounded-full', unresolvedCount > 0 ? 'bg-halt' : 'bg-ok')} />
            {unresolvedCount > 0 ? t('alerts.summary.firing', { count: unresolvedCount }) : t('alerts.tiles.allClear')}
          </span>
          <span className="text-muted-foreground">{t('alerts.summary.armed', { armed: armedCount, total: rules.length })}</span>
          {silencedCount > 0 && <span className="text-muted-foreground">{t('alerts.summary.silenced', { count: silencedCount })}</span>}
        </p>

        <section>
          <div className="mb-3">
            <h3 className="text-[15px] font-medium leading-tight">{t('alerts.rules.title')}</h3>
            <p className="mt-0.5 text-[13px] text-muted-foreground">{t('alerts.rules.desc')}</p>
          </div>

          {rulesIsError ? (
            <ErrorState error={rulesError} fallbackKey="alerts.loadFailed" onRetry={() => refetchRules()} />
          ) : rules.length === 0 ? (
            <EmptyState
              icon={Bell}
              title={t('alerts.noRules')}
              description={t('alerts.noRulesDesc')}
              action={
                <PermissionGate allowed={canManageEndpoints}>
                  <VerificationGate>
                    <Button onClick={openCreate}>
                      <Plus className="h-4 w-4" /> {t('alerts.createRule')}
                    </Button>
                  </VerificationGate>
                </PermissionGate>
              }
            />
          ) : (
            <Table className="text-[13px]">
              <TableHeader>
                <TableRow>
                  <TableHead>{t('alerts.rules.columns.rule')}</TableHead>
                  <TableHead>{t('alerts.rules.columns.condition')}</TableHead>
                  <TableHead className="hidden xl:table-cell">{t('alerts.rules.columns.channel')}</TableHead>
                  <TableHead className="hidden lg:table-cell">{t('alerts.rules.columns.severity')}</TableHead>
                  <TableHead>{t('alerts.rules.columns.state')}</TableHead>
                  <TableHead className="w-[150px] text-right"><span className="sr-only">{t('common.actions')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {sortedRules.map((rule) => {
                  const ChannelIcon = CHANNEL_ICON[rule.channel] ?? Bell;
                  const conditionText = t(`alerts.condition.${rule.alertType}`, {
                    v: rule.thresholdValue, w: rule.windowMinutes, defaultValue: conditionLabel(rule.alertType),
                  });
                  const firing = firingByRule.get(rule.id);
                  const state = ruleState(rule, firing);
                  return (
                    <TableRow key={rule.id} data-firing={state === 'firing' || undefined} className={cn(state === 'firing' && 'bg-halt-soft/60 hover:bg-halt-soft')}>
                      <TableCell className="relative max-w-[18rem]">
                        {state === 'firing' && <span aria-hidden className="absolute bottom-0 left-0 top-0 w-[2px] bg-halt max-md:hidden" />}
                        <p className="truncate">{rule.name}</p>
                        {rule.description && <p className="truncate text-[12px] text-muted-foreground">{rule.description}</p>}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {conditionText}
                      </TableCell>
                      <TableCell className="hidden xl:table-cell">
                        <span className="flex items-center gap-2 whitespace-nowrap">
                          <ChannelIcon className="h-3.5 w-3.5 text-muted-foreground" aria-hidden />
                          {channelLabel(rule.channel)}
                        </span>
                      </TableCell>
                      <TableCell className={cn('hidden lg:table-cell', STATUS_TEXT[kindOfSeverity(rule.severity)])}>
                        {t(`alerts.severities.${rule.severity}`)}
                      </TableCell>
                      <TableCell>
                        {state === 'firing' ? (
                          <span className="block">
                            <StatusBadge kind="halt" label={t('alerts.state.firing')} />
                            {firing?.currentValue != null && firing.thresholdValue != null && (
                              <span className="block pl-4 text-[12px] text-muted-foreground">
                                {t('alerts.state.firingValue', { current: firing.currentValue, threshold: firing.thresholdValue })}
                              </span>
                            )}
                          </span>
                        ) : (
                          <StatusBadge
                            kind={state === 'armed' ? 'ok' : state === 'snoozed' ? 'retry' : 'idle'}
                            label={state === 'snoozed' && rule.snoozedUntil
                              ? t('alerts.state.snoozedUntil', { time: formatRelativeFuture(rule.snoozedUntil) })
                              : t(`alerts.state.${state}`)}
                          />
                        )}
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center justify-end gap-1">
                          <Switch
                            checked={rule.enabled}
                            onCheckedChange={(v) => handleToggleRule(rule.id, v)}
                            aria-label={t('alerts.toggleRule', { name: rule.name })}
                            disabled={!canManageEndpoints}
                          />
                          {canManageEndpoints && (
                            <>
                              <Button
                                variant="ghost"
                                size="icon-sm"
                                onClick={() => openEdit(rule)}
                                aria-label={t('alerts.editRule', { name: rule.name })}
                                title={t('alerts.editRule', { name: rule.name })}
                              >
                                <Pencil className="h-3.5 w-3.5" />
                              </Button>
                              <Button
                                variant="ghost"
                                size="icon-sm"
                                onClick={() => handleMuteRule(rule.id, !rule.muted)}
                                aria-label={t(rule.muted ? 'alerts.unmute' : 'alerts.mute')}
                                title={t(rule.muted ? 'alerts.unmute' : 'alerts.mute')}
                              >
                                <VolumeX className={cn('h-3.5 w-3.5', rule.muted && 'text-foreground')} />
                              </Button>
                              <div className="relative">
                                <Button
                                  variant="ghost"
                                  size="icon-sm"
                                  onClick={() => setSnoozeDropdownId(snoozeDropdownId === rule.id ? null : rule.id)}
                                  aria-label={t('alerts.snooze')}
                                  title={t('alerts.snooze')}
                                  aria-expanded={snoozeDropdownId === rule.id}
                                >
                                  <Clock className="h-3.5 w-3.5" />
                                </Button>
                                {snoozeDropdownId === rule.id && (
                                  <div className="absolute right-0 top-full z-10 mt-1 min-w-[130px] border border-rail bg-popover py-1 shadow-elevated">
                                    {SNOOZE_HOURS.map((h) => (
                                      <button
                                        key={h}
                                        type="button"
                                        className="w-full px-3 py-2 text-left text-xs transition-colors hover:bg-secondary"
                                        onClick={() => { handleSnoozeRule(rule.id, h); setSnoozeDropdownId(null); }}
                                      >
                                        {t('alerts.snoozeFor', { hours: h })}
                                      </button>
                                    ))}
                                  </div>
                                )}
                              </div>
                              <Button
                                variant="ghost"
                                size="icon-sm"
                                className="text-muted-foreground hover:text-halt"
                                onClick={() => setDeleteRuleId(rule.id)}
                                aria-label={t('alerts.deleteRule')}
                                title={t('alerts.deleteRule')}
                              >
                                <Trash2 className="h-3.5 w-3.5" />
                              </Button>
                            </>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </section>

        <section>
          <div className="mb-3">
            <h3 className="text-sm font-medium leading-tight">{t('alerts.history.title')}</h3>
            <p className="mt-0.5 text-xs text-muted-foreground">{t('alerts.history.desc')}</p>
          </div>

          {eventsIsError ? (
            <ErrorState error={eventsError} fallbackKey="alerts.loadFailed" onRetry={() => refetchEvents()} />
          ) : events.length === 0 ? (
            <EmptyState icon={Bell} title={t('alerts.noEvents')} description={t('alerts.noEventsDesc')} />
          ) : (
            <div className="animate-fade-in">
              <Card className="overflow-hidden">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[112px]">{t('alerts.columns.severity')}</TableHead>
                      <TableHead>{t('alerts.columns.title')}</TableHead>
                      <TableHead className="w-[150px]">{t('alerts.columns.value')}</TableHead>
                      <TableHead className="w-[150px]">{t('alerts.columns.time')}</TableHead>
                      <TableHead className="w-[110px]">{t('alerts.columns.status')}</TableHead>
                      <TableHead className="w-[48px]"><span className="sr-only">{t('alerts.columns.details')}</span></TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {events.map((event) => {
                      const kind = kindOfSeverity(event.severity);
                      const isExpanded = expandedEventId === event.id;
                      const rule = rules.find((r) => r.id === event.alertRuleId);
                      const investigateUrl = projectId
                        ? `/admin/projects/${projectId}/deliveries?status=FAILED${rule?.endpointId ? `&endpointId=${rule.endpointId}` : ''}`
                        : null;
                      const overshoot = event.currentValue != null && event.thresholdValue
                        ? Math.min((event.currentValue / event.thresholdValue) * 100, 100)
                        : 0;
                      return (
                        <Fragment key={event.id}>
                          <TableRow
                            className={cn('cursor-pointer', event.resolved && 'opacity-60', isExpanded && 'bg-secondary/40')}
                            onClick={() => setExpandedEventId(isExpanded ? null : event.id)}
                          >
                            <TableCell>
                              <StatusBadge kind={kind} label={t(`alerts.severities.${event.severity}`)} />
                            </TableCell>
                            <TableCell className="sm:max-w-0">
                              <p className="truncate text-sm font-medium">{event.title}</p>
                              {event.message && !isExpanded && (
                                <p className="mt-0.5 truncate text-xs text-muted-foreground">{event.message}</p>
                              )}
                            </TableCell>
                            <TableCell>
                              {event.currentValue != null && event.thresholdValue != null ? (
                                <>
                                  <span className="font-mono text-xs tabular-nums">
                                    <span className={STATUS_TEXT[kind]}>{event.currentValue.toFixed(1)}</span>
                                    <span className="text-muted-foreground"> / {event.thresholdValue.toFixed(1)}</span>
                                  </span>
                                  <span className="relative mt-1 block h-1 w-full overflow-hidden bg-muted">
                                    <span
                                      className={cn('absolute inset-y-0 left-0', STATUS_FILL[kind])}
                                      style={{ width: `${overshoot}%` }}
                                    />
                                  </span>
                                </>
                              ) : (
                                <span className="font-mono text-xs text-muted-foreground">—</span>
                              )}
                            </TableCell>
                            <TableCell>
                              <span className="block text-sm">{formatRelativeTime(event.createdAt)}</span>
                              <span className="block font-mono text-[11px] text-muted-foreground">
                                {formatDateTime(event.createdAt)}
                              </span>
                            </TableCell>
                            <TableCell>
                              <StatusBadge
                                kind={event.resolved ? 'ok' : 'halt'}
                                label={t(event.resolved ? 'alerts.resolved' : 'alerts.active')}
                                icon={false}
                              />
                            </TableCell>
                            <TableCell>
                              <ChevronDown
                                className={cn('h-3.5 w-3.5 text-muted-foreground transition-transform', isExpanded && 'rotate-180')}
                                aria-hidden
                              />
                            </TableCell>
                          </TableRow>
                          {isExpanded && (
                            <TableRow className="bg-secondary/20 hover:bg-secondary/20">
                              <TableCell colSpan={6} className="py-3">
                                <div className="space-y-3 pl-2">
                                  {event.message && <p className="text-sm text-muted-foreground">{event.message}</p>}
                                  {rule && (
                                    <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                                      <span>{t('alerts.triggeredBy')}</span>
                                      <Badge variant="secondary">{rule.name}</Badge>
                                      <Badge variant="outline">{conditionLabel(rule.alertType)}</Badge>
                                    </div>
                                  )}
                                  {event.resolvedAt && (
                                    <p className="text-xs text-muted-foreground">
                                      {t('alerts.resolvedAtLabel', { time: formatDateTime(event.resolvedAt) })}
                                    </p>
                                  )}
                                  <div className="flex flex-wrap items-center gap-2">
                                    {investigateUrl && (
                                      <Button variant="outline" size="sm" asChild>
                                        <Link to={investigateUrl}>
                                          <Search className="h-3.5 w-3.5" />
                                          {t('alerts.investigate')}
                                        </Link>
                                      </Button>
                                    )}
                                    {!event.resolved && canManageEndpoints && (
                                      <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={(e) => { e.stopPropagation(); handleResolve(event.id); }}
                                      >
                                        <Check className="h-3.5 w-3.5" />
                                        {t('alerts.resolve')}
                                      </Button>
                                    )}
                                  </div>
                                </div>
                              </TableCell>
                            </TableRow>
                          )}
                        </Fragment>
                      );
                    })}
                  </TableBody>
                </Table>
              </Card>
              <TablePagination
                page={eventsPage}
                pageSize={eventsPageSize}
                totalElements={eventsData?.totalElements ?? 0}
                totalPages={eventsData?.totalPages ?? 0}
                onPageChange={setEventsPage}
                onPageSizeChange={setEventsPageSize}
              />
            </div>
          )}
        </section>

      </div>

      <Dialog open={showCreateDialog} onOpenChange={setShowCreateDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t(editingRule ? 'alerts.editDialog.title' : 'alerts.createDialog.title')}</DialogTitle>
            <DialogDescription>{t('alerts.createDialog.description')}</DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label htmlFor="alert-name">{t('alerts.form.name')}</Label>
              <Input
                id="alert-name"
                value={formName}
                onChange={(e) => setFormName(e.target.value)}
                placeholder={t('alerts.form.namePlaceholder')}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="alert-type">{t('alerts.form.type')}</Label>
              <Select id="alert-type" value={formType} onChange={(e) => changeType(e.target.value)}>
                {conditions.map((c) => <option key={c.id} value={c.id}>{conditionLabel(c.id)}</option>)}
              </Select>
              {t(`alerts.types.${formType}.hint`, { defaultValue: '' }) && (
                <p className="text-xs text-muted-foreground">{t(`alerts.types.${formType}.hint`)}</p>
              )}
            </div>
            <ConfigSchemaFields
              schema={conditionSchema}
              values={formConditionValues}
              onChange={(name, value) => setFormConditionValues((v) => ({ ...v, [name]: value }))}
              idPrefix="alert-condition"
              label={fieldLabel('conditionFields', formType)}
              hint={fieldHint('conditionFields', formType)}
              endpoints={endpointOptions}
            />
            <div className="space-y-2">
              <Label htmlFor="alert-severity">{t('alerts.form.severity')}</Label>
              <Select id="alert-severity" value={formSeverity} onChange={(e) => setFormSeverity(e.target.value as AlertSeverity)}>
                {SEVERITY_VALUES.map((v) => <option key={v} value={v}>{t(`alerts.severities.${v}`)}</option>)}
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="alert-description">{t('alerts.form.description')}</Label>
              <Input
                id="alert-description"
                value={formDescription}
                onChange={(e) => setFormDescription(e.target.value)}
                placeholder={t('alerts.form.descriptionPlaceholder')}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="alert-channel">{t('alerts.form.channel')}</Label>
              <Select id="alert-channel" value={formChannel} onChange={(e) => changeChannel(e.target.value)}>
                {channels.map((c) => (
                  <option key={c.id} value={c.id} disabled={formSeverity === 'INFO' && c.pages}>
                    {channelLabel(c.id)}
                  </option>
                ))}
              </Select>
            </div>
            <ConfigSchemaFields
              schema={channelSchema}
              values={formConfig}
              onChange={(name, value) => setFormConfig((v) => ({ ...v, [name]: value }))}
              idPrefix="alert-channel"
              storedSecrets={storedSecrets}
              label={fieldLabel('channelFields', formChannel)}
              hint={fieldHint('channelFields', formChannel)}
              optionLabel={(name, option) =>
                t(`alerts.channelFields.${formChannel}.${name}Options.${option}`, { defaultValue: option })}
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowCreateDialog(false)}>{t('common.cancel')}</Button>
            <Button
              onClick={handleSubmit}
              disabled={!formName || !formType || !isFilled(conditionSchema, formConditionValues)
                || !isFilled(channelSchema, formConfig, storedSecrets) || createRule.isPending || updateRule.isPending}
            >
              {(createRule.isPending || updateRule.isPending) && <Loader2 className="h-4 w-4 animate-spin" />}
              {t(editingRule ? 'alerts.editDialog.submit' : 'alerts.createDialog.submit')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteRuleId} onOpenChange={() => setDeleteRuleId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('alerts.deleteDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('alerts.deleteDialog.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} className={buttonVariants({ variant: 'destructive' })}>
              {t('common.delete')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
