package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.exception.ForbiddenException;

/**
 * Not a minimum membership role: an API key is neither above nor below a Viewer, its
 * permissions come from its scope.
 */
public enum AccessLevel {

    READ {
        @Override
        public void require(MembershipRole role, ApiKeyScope apiKeyScope) {
        }
    },

    /** Rejects a Viewer and a READ_ONLY API key. */
    WRITE {
        @Override
        public void require(MembershipRole role, ApiKeyScope apiKeyScope) {
            if (role == MembershipRole.VIEWER) {
                throw new ForbiddenException("Viewers have read-only access");
            }
            if (role == MembershipRole.API_KEY && apiKeyScope == ApiKeyScope.READ_ONLY) {
                throw new ForbiddenException("API key has read-only access. Write operations are not permitted.");
            }
        }
    },

    /** Owners only, so it also excludes every API key. */
    OWNER {
        @Override
        public void require(MembershipRole role, ApiKeyScope apiKeyScope) {
            if (role != MembershipRole.OWNER) {
                throw new ForbiddenException("Only owners can perform this action");
            }
        }
    };

    public abstract void require(MembershipRole role, ApiKeyScope apiKeyScope);
}
