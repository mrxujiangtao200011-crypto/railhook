import { useAuth } from './auth.store';
import { hasMinRole, type Role } from './ProtectedRoute';

export type { Role };

export function usePermissions() {
    const { user } = useAuth();
    const role: Role = (user?.role || 'VIEWER') as Role;
    const emailVerified = user?.user?.status !== 'PENDING_VERIFICATION';

    return {
        role,
        emailVerified,
        isOwner: role === 'OWNER',
        isDeveloper: role === 'DEVELOPER',
        isViewer: role === 'VIEWER',

        canCreateProject: hasMinRole(role, 'DEVELOPER'),
        canDeleteProject: role === 'OWNER',

        canManageEndpoints: hasMinRole(role, 'DEVELOPER'),

        canSendEvents: hasMinRole(role, 'DEVELOPER'),

        canReplayDeliveries: hasMinRole(role, 'DEVELOPER'),

        canManageSubscriptions: hasMinRole(role, 'DEVELOPER'),

        canManageApiKeys: hasMinRole(role, 'DEVELOPER'),

        canManageDlq: hasMinRole(role, 'DEVELOPER'),

        canManageTestEndpoints: hasMinRole(role, 'DEVELOPER'),

        canManageIncomingSources: hasMinRole(role, 'DEVELOPER'),
        canReplayIncomingEvents: hasMinRole(role, 'DEVELOPER'),

        canManageMembers: role === 'OWNER',

        canManageOrgSettings: role === 'OWNER',

        canManagePiiRules: hasMinRole(role, 'DEVELOPER'),

        canCreateDebugLinks: hasMinRole(role, 'DEVELOPER'),
    };
}
