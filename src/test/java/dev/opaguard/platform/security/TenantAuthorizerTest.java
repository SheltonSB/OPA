package dev.opaguard.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantAuthorizerTest {
    private final TenantAuthorizer authorizer = new TenantAuthorizer();

    @Test
    void acceptsMatchingTenantAndRejectsAnotherTenant() {
        UUID allowed = UUID.randomUUID();
        Jwt jwt = new Jwt("token", Instant.EPOCH, Instant.MAX, Map.of("alg", "none"), Map.of("org_id", allowed.toString()));
        var auth = new UsernamePasswordAuthenticationToken(jwt, "token", List.of(new SimpleGrantedAuthority("SCOPE_benchmark.run")));

        assertThatCode(() -> authorizer.requireAccess(allowed, auth)).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.requireAccess(UUID.randomUUID(), auth)).isInstanceOf(AccessDeniedException.class);
    }
}
