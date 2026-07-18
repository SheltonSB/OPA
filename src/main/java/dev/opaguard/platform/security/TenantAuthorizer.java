package dev.opaguard.platform.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Enforces organization membership from trusted JWT claims at every API boundary.
 *
 * @author Shelton Bumhe
 */
@Component
public class TenantAuthorizer {
    /**
     * Verifies that the principal may act on the requested organization.
     *
     * @param organizationId requested tenant boundary
     * @param authentication current Spring Security authentication
     * @throws org.springframework.security.access.AccessDeniedException when access is not granted
     */
    public void requireAccess(UUID organizationId, Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AccessDeniedException("Authenticated tenant principal required");
        }
        boolean platformAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("SCOPE_platform.admin"));
        if (platformAdmin) return;

        String primaryOrg = jwt.getClaimAsString("org_id");
        Collection<String> organizations = jwt.getClaimAsStringList("org_ids");
        boolean allowed = organizationId.toString().equals(primaryOrg)
                || (organizations != null && organizations.contains(organizationId.toString()));
        if (!allowed) {
            throw new AccessDeniedException("Token does not grant access to this organization");
        }
    }
}
