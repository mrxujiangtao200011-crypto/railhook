import { describe, it, expect } from 'vitest';
import { cn } from '../utils';

describe('cn', () => {
  it('drops falsy classes and lets the later Tailwind utility win', () => {
    expect(cn('p-4 text-red-500', false && 'hidden', undefined, 'p-2')).toBe('text-red-500 p-2');
  });
});
