import type { TFunction } from 'i18next';
import type { IncomingProviderResponse } from '../types/api.types';

export const GENERIC_PROVIDER = 'GENERIC';

export function providerLabel(
  id: string, providers: IncomingProviderResponse[] | undefined, t: TFunction,
): string {
  if (id === GENERIC_PROVIDER) return t('incomingSources.genericProvider');
  return providers?.find((p) => p.id === id)?.displayName ?? id;
}
