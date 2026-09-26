import { describe, expect, it } from 'vitest';
import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';

import { usePermissions } from '../usePermissions';
import { AuthContext, type AuthState } from '../auth.store';
import type { CurrentUserResponse } from '../../types/api.types';

function permissionsOf(role: string | undefined, status = 'ACTIVE') {
  const auth: AuthState = {
    user: { user: { id: 'u1', email: 'a@example.com', fullName: 'A', status }, role } as unknown as CurrentUserResponse,
    token: 't',
    login: () => {},
    logout: () => {},
    updateUser: () => {},
    isAuthenticated: true,
  };
  const wrapper = ({ children }: { children: ReactNode }) => <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>;
  return renderHook(() => usePermissions(), { wrapper }).result.current;
}

describe('usePermissions', () => {
  it.each([
    ['OWNER', { canManageEndpoints: true, canDeleteProject: true, canManageMembers: true, canManageOrgSettings: true }],
    ['DEVELOPER', { canManageEndpoints: true, canDeleteProject: false, canManageMembers: false, canManageOrgSettings: false }],
    ['VIEWER', { canManageEndpoints: false, canDeleteProject: false, canManageMembers: false, canManageOrgSettings: false }],
  ])('grants a %s what the role allows', (role, expected) => {
    expect(permissionsOf(role)).toMatchObject({ role, ...expected });
  });

  it('treats a user with no role as a Viewer', () => {
    expect(permissionsOf(undefined)).toMatchObject({ role: 'VIEWER', canManageEndpoints: false });
  });

  it.each([
    ['ACTIVE', true],
    ['PENDING_VERIFICATION', false],
  ])('reports a %s account as email-verified: %s', (status, verified) => {
    expect(permissionsOf('OWNER', status).emailVerified).toBe(verified);
  });
});
