import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Shield, Plus, Trash2, Loader2, Sparkles, EyeOff, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { showApiError, showSuccess } from '../lib/toast';
import PageSkeleton, { SkeletonRows } from '../components/PageSkeleton';
import PiiPreview from '../components/PiiPreview';
import PageHeader from '../components/PageHeader';
import EmptyState, { ErrorState } from '../components/EmptyState';
import { EnabledBadge } from '../components/StatusBadge';
import { RuleStats, RuleRow, MatchExpression, RuleActionChip } from '../components/RuleLayout';
import type { PiiMaskingRuleResponse, MaskStyle } from '../api/piiRules.api';
import { usePiiRules, useSeedPiiRules, useCreatePiiRule, useUpdatePiiRule, useDeletePiiRule } from '../api/queries';
import { Button, buttonVariants } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Select } from '../components/ui/select';
import { Switch } from '../components/ui/switch';
import { Badge } from '../components/ui/badge';
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from '../components/ui/alert-dialog';
import { usePermissions } from '../auth/usePermissions';
import PermissionGate from '../components/PermissionGate';
import VerificationGate from '../components/VerificationGate';

const MASK_STYLE_VALUES: MaskStyle[] = ['PARTIAL', 'FULL', 'HASH'];

export default function PiiRulesPage() {
  const { t } = useTranslation();
  const { projectId } = useParams<{ projectId: string }>();
  const { canManagePiiRules } = usePermissions();

  const [deleteId, setDeleteId] = useState<string | null>(null);
  const [showAddForm, setShowAddForm] = useState(false);
  const [newPatternName, setNewPatternName] = useState('');
  const [newJsonPath, setNewJsonPath] = useState('');
  const [newMaskStyle, setNewMaskStyle] = useState<MaskStyle>('PARTIAL');

  const rulesQuery = usePiiRules(projectId);
  const seedRules = useSeedPiiRules(projectId!);
  const createRule = useCreatePiiRule(projectId!);
  const updateRule = useUpdatePiiRule(projectId!);
  const deleteRule = useDeletePiiRule(projectId!);
  const { error: loadError, refetch: loadRules } = rulesQuery;
  const rules = rulesQuery.data ?? [];
  const seeding = seedRules.isPending;
  const creating = createRule.isPending;
  const deleting = deleteRule.isPending;

  useEffect(() => {
    if (loadError) showApiError(loadError, 'piiRules.toast.loadFailed', { retry: () => loadRules() });
  }, [loadError, loadRules]);

  const handleSeedDefaults = async () => {
    if (!projectId) return;
    try {
      await seedRules.mutateAsync();
      showSuccess(t('piiRules.toast.seeded'));
    } catch (err: any) {
      showApiError(err, 'piiRules.toast.seedFailed');
    }
  };

  const handleCreate = async () => {
    if (!projectId || !newPatternName.trim()) return;
    try {
      await createRule.mutateAsync({
        patternName: newPatternName.trim(),
        jsonPath: newJsonPath.trim() || undefined,
        maskStyle: newMaskStyle,
        enabled: true,
      });
      setNewPatternName('');
      setNewJsonPath('');
      setNewMaskStyle('PARTIAL');
      setShowAddForm(false);
      showSuccess(t('piiRules.toast.created'));
    } catch (err: any) {
      showApiError(err, 'piiRules.toast.createFailed');
    }
  };

  const patchRule = async (rule: PiiMaskingRuleResponse, patch: { maskStyle?: MaskStyle; enabled?: boolean }) => {
    if (!projectId) return;
    try {
      await updateRule.mutateAsync({
        id: rule.id,
        data: {
          patternName: rule.patternName,
          maskStyle: patch.maskStyle ?? rule.maskStyle,
          enabled: patch.enabled ?? rule.enabled,
        },
      });
    } catch (err: any) {
      showApiError(err, 'piiRules.toast.updateFailed');
    }
  };

  const handleDelete = async () => {
    if (!deleteId || !projectId) return;
    try {
      await deleteRule.mutateAsync(deleteId);
      showSuccess(t('piiRules.toast.deleted'));
    } catch (err: any) {
      showApiError(err, 'piiRules.toast.deleteFailed');
    } finally {
      setDeleteId(null);
    }
  };

  if (rulesQuery.isLoading && !rulesQuery.data) {
    return <PageSkeleton><SkeletonRows count={4} height="h-12" /></PageSkeleton>;
  }

  const enabledCount = rules.filter((r) => r.enabled).length;
  const builtinCount = rules.filter((r) => r.ruleType === 'BUILTIN').length;

  return (
    <div className="p-4 lg:p-6">
      <PageHeader
        eyebrow={t('piiRules.count', { count: rules.length })}
        title={t('piiRules.title')}
        description={t('piiRules.subtitle')}
        actions={!loadError && rules.length > 0 ? (
          <PermissionGate allowed={canManagePiiRules}>
            <VerificationGate>
              <Button variant={showAddForm ? 'secondary' : 'default'} onClick={() => setShowAddForm(!showAddForm)}>
                {showAddForm ? <X className="h-4 w-4" /> : <Plus className="h-4 w-4" />}
                {showAddForm ? t('common.cancel') : t('piiRules.addRule')}
              </Button>
            </VerificationGate>
          </PermissionGate>
        ) : undefined}
      />

      {showAddForm && (
        <section className="mb-4 border border-rail bg-card shadow-card">
          <header className="border-b border-rail px-4 py-2.5">
            <div className="mono-label">{t('piiRules.newRuleEyebrow')}</div>
            <h3 className="text-[13px] font-medium">{t('piiRules.newRule')}</h3>
          </header>
          <div className="space-y-4 p-4">
            <div className="grid gap-4 sm:grid-cols-3">
              <div className="space-y-1.5">
                <Label htmlFor="pii-pattern" className="text-xs">{t('piiRules.patternName')}</Label>
                <Input
                  id="pii-pattern"
                  placeholder={t('piiRules.patternNamePlaceholder')}
                  value={newPatternName}
                  onChange={(e) => setNewPatternName(e.target.value)}
                  className="font-mono text-sm"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="pii-path" className="text-xs">{t('piiRules.jsonPath')}</Label>
                <Input
                  id="pii-path"
                  placeholder="$.user.ssn"
                  value={newJsonPath}
                  onChange={(e) => setNewJsonPath(e.target.value)}
                  className="font-mono text-sm"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="pii-style" className="text-xs">{t('piiRules.maskStyle')}</Label>
                <Select id="pii-style" value={newMaskStyle} onChange={(e) => setNewMaskStyle(e.target.value as MaskStyle)}>
                  {MASK_STYLE_VALUES.map((value) => (
                    <option key={value} value={value}>{t(`piiRules.maskStyles.${value}`)}</option>
                  ))}
                </Select>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Button onClick={handleCreate} disabled={creating || !newPatternName.trim()}>
                {creating && <Loader2 className="h-4 w-4 animate-spin" />}
                {t('common.create')}
              </Button>
              <Button variant="ghost" onClick={() => setShowAddForm(false)}>{t('common.cancel')}</Button>
            </div>
          </div>
        </section>
      )}

      {loadError ? (
        <ErrorState error={loadError} fallbackKey="piiRules.toast.loadFailed" onRetry={() => loadRules()} retrying={rulesQuery.isFetching} />
      ) : rules.length === 0 ? (
        <EmptyState
          icon={Shield}
          title={t('piiRules.noRules')}
          description={t('piiRules.noRulesHint')}
          action={
            <PermissionGate allowed={canManagePiiRules}>
              <VerificationGate>
                <Button onClick={handleSeedDefaults} disabled={seeding}>
                  {seeding ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
                  {t('piiRules.seedDefaults')}
                </Button>
              </VerificationGate>
            </PermissionGate>
          }
        />
      ) : (
        <div className="space-y-4">
          <RuleStats
            items={[
              { label: t('piiRules.stats.total'), value: rules.length },
              { label: t('piiRules.stats.active'), value: enabledCount },
              { label: t('piiRules.stats.builtin'), value: builtinCount },
              { label: t('piiRules.stats.custom'), value: rules.length - builtinCount },
            ]}
          />

          <ul className="space-y-2.5">
            {rules.map((rule) => (
              <li key={rule.id}>
                <RuleRow
                  muted={!rule.enabled}
                  name={<span className="font-mono">{rule.patternName}</span>}
                  meta={
                    <Badge variant={rule.ruleType === 'BUILTIN' ? 'secondary' : 'outline'} className="text-[10px]">
                      {rule.ruleType === 'BUILTIN' ? t('piiRules.builtin') : t('piiRules.custom')}
                    </Badge>
                  }
                  match={
                    <MatchExpression title={rule.jsonPath || undefined}>
                      {rule.jsonPath || t('piiRules.anyField')}
                    </MatchExpression>
                  }
                  then={
                    <span className="flex flex-wrap items-center gap-1.5">
                      {canManagePiiRules ? (
                        <Select
                          value={rule.maskStyle}
                          onChange={(e) => patchRule(rule, { maskStyle: e.target.value as MaskStyle })}
                          className="h-7 w-32 text-xs"
                          aria-label={t('piiRules.maskStyle')}
                        >
                          {MASK_STYLE_VALUES.map((value) => (
                            <option key={value} value={value}>{t(`piiRules.maskStyles.${value}`)}</option>
                          ))}
                        </Select>
                      ) : (
                        <RuleActionChip icon={EyeOff} label={t(`piiRules.maskStyles.${rule.maskStyle}`)} />
                      )}
                    </span>
                  }
                  status={<EnabledBadge enabled={rule.enabled} />}
                  controls={
                    <>
                      {canManagePiiRules && (
                        <Switch
                          checked={rule.enabled}
                          onCheckedChange={() => patchRule(rule, { enabled: !rule.enabled })}
                          aria-label={t(rule.enabled ? 'common.disable' : 'common.enable')}
                        />
                      )}
                      {canManagePiiRules && rule.ruleType !== 'BUILTIN' && (
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          onClick={() => setDeleteId(rule.id)}
                          className="text-muted-foreground hover:text-halt"
                          title={t('common.delete')}
                          aria-label={t('common.delete')}
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      )}
                    </>
                  }
                />
              </li>
            ))}
          </ul>

          <PiiPreview projectId={projectId!} />
        </div>
      )}

      <AlertDialog open={!!deleteId} onOpenChange={(open) => !open && setDeleteId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t('piiRules.deleteDialog.title')}</AlertDialogTitle>
            <AlertDialogDescription>{t('piiRules.deleteDialog.description')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>{t('common.cancel')}</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete} disabled={deleting} className={buttonVariants({ variant: 'destructive' })}>
              {deleting && <Loader2 className="h-4 w-4 animate-spin" />}
              {t('common.delete')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
