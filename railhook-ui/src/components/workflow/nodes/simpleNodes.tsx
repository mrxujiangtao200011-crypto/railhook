import { memo } from 'react';
import { type NodeProps } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import BaseNode from './BaseNode';
import type { NodeRole } from './nodeTypes';

type NodeData = Record<string, unknown>;

interface SimpleNodeConfig {
  type: string;
  role: NodeRole;
  icon: string;
  subtitle: (d: NodeData, t: TFunction) => string;
  hasInput?: boolean;
}

const truncate = (value: string, max: number) => (value.length > max ? value.substring(0, max) + '…' : value);

function simpleNode({ type, role, icon, subtitle, hasInput }: SimpleNodeConfig) {
  function SimpleNode({ data, selected }: NodeProps) {
    const { t } = useTranslation();
    const d = data as NodeData;
    return (
      <BaseNode
        role={role}
        icon={icon}
        label={String(d.label || t(`workflows.nodeTypes.${type}.label`))}
        subtitle={subtitle(d, t)}
        selected={selected}
        hasInput={hasInput}
      />
    );
  }
  return memo(SimpleNode);
}

export const TriggerNode = simpleNode({
  type: 'webhookTrigger', role: 'trigger', icon: '⚡', hasInput: false,
  subtitle: (d, t) => (d.eventTypePattern ? String(d.eventTypePattern) : t('workflows.nodeStatus.allEvents')),
});

export const FilterNode = simpleNode({
  type: 'filter', role: 'logic', icon: '🔀',
  subtitle: (d, t) => (d.conditions != null ? t('workflows.nodeStatus.conditionsSet') : t('workflows.nodeStatus.noConditions')),
});

export const TransformNode = simpleNode({
  type: 'transform', role: 'logic', icon: '🔄',
  subtitle: (d, t) => (d.template && String(d.template).trim() !== '{}'
    ? t('workflows.nodeStatus.templateConfigured')
    : t('workflows.nodeStatus.noTemplate')),
});

export const HttpNode = simpleNode({
  type: 'http', role: 'action', icon: '🌐',
  subtitle: (d, t) => (d.url
    ? `${String(d.method || 'POST')} ${truncate(String(d.url), 30)}`
    : t('workflows.nodeStatus.notConfigured')),
});

export const SlackNode = simpleNode({
  type: 'slack', role: 'action', icon: '💬',
  subtitle: (d, t) => (d.channel ? String(d.channel) : '')
    || (d.webhookUrl && String(d.webhookUrl).length > 0
      ? t('workflows.nodeStatus.webhookConfigured')
      : t('workflows.nodeStatus.notConfigured')),
});

export const DeliveryNode = simpleNode({
  type: 'delivery', role: 'action', icon: '📦',
  subtitle: (d, t) => (d.endpointId ? truncate(String(d.endpointId), 12) : t('workflows.nodeStatus.notConfigured')),
});

export const DelayNode = simpleNode({
  type: 'delay', role: 'logic', icon: '⏱️',
  subtitle: (d, t) => t('workflows.nodeStatus.delaySeconds', { count: d.delaySeconds ? Number(d.delaySeconds) : 5 }),
});

export const CreateEventNode = simpleNode({
  type: 'createEvent', role: 'action', icon: '📤',
  subtitle: (d, t) => (d.eventType ? String(d.eventType) : t('workflows.nodeStatus.notConfigured')),
});
