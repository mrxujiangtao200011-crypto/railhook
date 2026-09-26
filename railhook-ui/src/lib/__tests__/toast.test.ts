import { describe, it, expect, vi, beforeEach } from 'vitest';
import { toast } from 'sonner';

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}));

vi.mock('../../i18n', () => ({
  default: {
    t: (key: string) => {
      const translations: Record<string, string> = {
        'toast.retry': 'Retry',
        'toast.errors.unauthorized': 'Unauthorized',
        'toast.errors.forbidden': 'Forbidden',
        'toast.errors.notFound': 'Not found',
        'toast.errors.conflict': 'Conflict',
        'toast.errors.validation': 'Validation error',
        'toast.errors.tooManyRequests': 'Too many requests',
        'toast.errors.server': 'Server error',
        'toast.errors.network': 'Network error. Check your connection and try again.',
        'toast.fallback': 'Something went wrong',
      };
      return translations[key] || key;
    },
    exists: (key: string) => key.startsWith('toast.'),
  },
}));

import { showApiError, showSuccess, resolveErrorMessage, isNetworkError } from '../toast';

describe('toast utilities', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('showApiError', () => {
    it('uses API message when available', () => {
      const err = { response: { status: 400, data: { message: 'Bad input' } } };
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith('Bad input', expect.any(Object));
    });

    it.each([
      [401, 'Unauthorized'],
      [403, 'Forbidden'],
      [429, 'Too many requests'],
      [500, 'Server error'],
    ])('maps a bare %i to its message', (status, message) => {
      showApiError({ response: { status, data: {} } }, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith(message, expect.any(Object));
    });

    it('falls back to i18n key when no status mapping', () => {
      const err = {};
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith('Something went wrong', expect.any(Object));
    });

    it('adds retry action when provided', () => {
      const retryFn = vi.fn();
      const err = {};
      showApiError(err, 'toast.fallback', { retry: retryFn });
      expect(toast.error).toHaveBeenCalledWith(
        'Something went wrong',
        expect.objectContaining({ action: expect.any(Object) })
      );
    });

    it('deduplicates by fallback key + API message', () => {
      const err = { response: { status: 400, data: { message: 'Duplicate' } } };
      showApiError(err, 'toast.fallback');
      expect(toast.error).toHaveBeenCalledWith('Duplicate', expect.objectContaining({
        id: 'toast.fallback::Duplicate',
      }));
    });
  });

  describe('isNetworkError', () => {
    it.each([
      ['a request with no response (backend down)', { request: {}, message: 'Network Error' }, true],
      ['axios ERR_NETWORK', { code: 'ERR_NETWORK' }, true],
      ['an axios timeout', { code: 'ECONNABORTED' }, true],
      ['a server that responded, even with a 5xx', { response: { status: 500 }, request: {} }, false],
      ['a plain object with no axios shape', {}, false],
      ['null', null, false],
    ])('is %s: %s', (_, err, expected) => {
      expect(isNetworkError(err)).toBe(expected);
    });
  });

  describe('resolveErrorMessage', () => {
    it('prioritizes the network-down message over the fallback key', () => {
      const err = { request: {}, message: 'Network Error' };
      expect(resolveErrorMessage(err, 'toast.fallback')).toBe('Network error. Check your connection and try again.');
    });
  });

  describe('showSuccess', () => {
    it.each([
      ['an i18n key translated', 'toast.fallback', 'Something went wrong'],
      ['a plain string as it is', 'Created successfully', 'Created successfully'],
    ])('shows %s', (_, input, shown) => {
      showSuccess(input);
      expect(toast.success).toHaveBeenCalledWith(shown, expect.any(Object));
    });
  });
});
