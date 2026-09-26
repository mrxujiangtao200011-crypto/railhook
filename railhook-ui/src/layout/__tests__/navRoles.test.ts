import { describe, it, expect } from 'vitest';
import { SETTINGS_SECTION, PROJECT_SECTIONS, requiredRoleFor } from '../nav.config';
import { hasMinRole, type Role } from '../../auth/ProtectedRoute';

const ROLES: Role[] = ['VIEWER', 'DEVELOPER', 'OWNER'];

/** Sidebar and router both read requiredRoleFor; separate lists once showed a link that denied access. */
describe('route roles', () => {
  it.each(['/admin/settings', '/admin/dashboard', '/admin/nothing-here'])('demands no role for %s: the profile, and paths no nav entry claims', (path) => {
    expect(requiredRoleFor(path)).toBeUndefined();
  });

  it('keeps the organization-level pages owner-only', () => {
    expect(requiredRoleFor('/admin/org-settings')).toBe('OWNER');
    expect(requiredRoleFor('/admin/members')).toBe('OWNER');
    expect(requiredRoleFor('/admin/billing')).toBe('OWNER');
  });

  it.each([
    ['settings', SETTINGS_SECTION.tabs],
    ['project', PROJECT_SECTIONS.flatMap((section) => [section, ...section.tabs])],
  ] as const)('offers a %s tab exactly where the guard would let the member through', (_, entries) => {
    for (const role of ROLES) {
      for (const entry of entries) {
        const offered = !entry.requiredRole || hasMinRole(role, entry.requiredRole);
        const required = requiredRoleFor(entry.path('project-1'));
        const admitted = !required || hasMinRole(role, required);
        expect(`${role} ${entry.nameKey} offered=${offered}`)
          .toBe(`${role} ${entry.nameKey} offered=${admitted}`);
      }
    }
  });
});
