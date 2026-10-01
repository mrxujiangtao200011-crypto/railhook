import { useTranslation } from 'react-i18next';
import type { ConfigProperty, ConfigSchema } from '../types/api.types';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Select } from './ui/select';

export interface ConfigSchemaFieldsProps {
  schema: ConfigSchema;
  values: Record<string, string>;
  onChange: (name: string, value: string) => void;
  idPrefix: string;
  /** Secrets already stored: their inputs stay empty, since the API never returns them. */
  storedSecrets?: string[];
  label: (name: string, property: ConfigProperty) => string;
  hint: (name: string, property: ConfigProperty) => string | undefined;
  optionLabel?: (name: string, option: string) => string;
  endpoints?: { id: string; label: string }[];
}

export function isFilled(schema: ConfigSchema, values: Record<string, string>, storedSecrets: string[] = []): boolean {
  return schema.required.every((name) => (values[name] ?? '').trim() !== '' || storedSecrets.includes(name));
}

export default function ConfigSchemaFields({
  schema, values, onChange, idPrefix, storedSecrets = [], label, hint, optionLabel, endpoints = [],
}: ConfigSchemaFieldsProps) {
  const { t } = useTranslation();
  return (
    <>
      {Object.entries(schema.properties).map(([name, property]) => {
        const id = `${idPrefix}-${name}`;
        const value = values[name] ?? '';
        const stored = property.writeOnly && storedSecrets.includes(name);
        const help = hint(name, property);
        return (
          <div key={name} className="space-y-2">
            <Label htmlFor={id}>{label(name, property)}</Label>
            {property.enum ? (
              <Select id={id} value={value} onChange={(e) => onChange(name, e.target.value)}>
                {property.enum.map((option) => (
                  <option key={option} value={option}>{optionLabel?.(name, option) ?? option}</option>
                ))}
              </Select>
            ) : property.format === 'endpoint-id' ? (
              <Select id={id} value={value} onChange={(e) => onChange(name, e.target.value)}>
                <option value="">{t('configSchema.chooseEndpoint')}</option>
                {endpoints.map((endpoint) => <option key={endpoint.id} value={endpoint.id}>{endpoint.label}</option>)}
              </Select>
            ) : (
              <Input
                id={id}
                type={property.writeOnly ? 'password' : property.type === 'string' ? 'text' : 'number'}
                autoComplete={property.writeOnly ? 'off' : undefined}
                value={value}
                onChange={(e) => onChange(name, e.target.value)}
                placeholder={stored ? t('configSchema.secretStored') : undefined}
              />
            )}
            {help && <p className="text-xs text-muted-foreground">{help}</p>}
          </div>
        );
      })}
    </>
  );
}
