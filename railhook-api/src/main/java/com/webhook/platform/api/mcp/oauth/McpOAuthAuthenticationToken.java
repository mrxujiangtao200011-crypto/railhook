package com.webhook.platform.api.mcp.oauth;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.security.ApiKeyAuthenticationToken;

import java.util.Collections;
import java.util.UUID;
import lombok.Getter;

/**
 * Extends ApiKeyAuthenticationToken because a grant is the same (organization, project, scope)
 * triple as a key, so tenancy, audit and scope checks need no second branch. The credential is
 * the grant id, never the bearer token.
 */
@Getter
public class McpOAuthAuthenticationToken extends ApiKeyAuthenticationToken {

    private final UUID grantId;

    public McpOAuthAuthenticationToken(UUID grantId, UUID projectId, UUID organizationId, ApiKeyScope scope) {
        super("oauth-grant:" + grantId, projectId, organizationId, scope, Collections.emptyList());
        this.grantId = grantId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof McpOAuthAuthenticationToken token && token.grantId.equals(grantId) && super.equals(other);
    }

    @Override
    public int hashCode() {
        return grantId.hashCode();
    }
}
