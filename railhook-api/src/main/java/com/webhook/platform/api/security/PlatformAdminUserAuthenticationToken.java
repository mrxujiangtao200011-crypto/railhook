package com.webhook.platform.api.security;

import lombok.Getter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Minted only on the admin paths, so the authority never rides along on tenant requests made
 * with the same JWT. Lacks the operator-token authority, which stays with the deployment's token.
 */
@Getter
public class PlatformAdminUserAuthenticationToken extends JwtAuthenticationToken {

    private final String email;

    public PlatformAdminUserAuthenticationToken(JwtAuthenticationToken signedIn, String email) {
        super(signedIn.getUserId(), signedIn.getOrganizationId(), signedIn.getRole(), signedIn.isEmailVerified(),
                List.of(new SimpleGrantedAuthority(PlatformAdminAuthenticationToken.AUTHORITY)));
        this.email = email;
    }
}
