package dev.opaguard.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Configures stateless OAuth 2 resource-server security and restrictive HTTP headers.
 *
 * <p>CSRF is disabled only because platform APIs do not use browser sessions or
 * cookies. Every non-health route is denied unless explicitly authorized.</p>
 *
 * @author Shelton Bumhe
 */
@Configuration
@ConditionalOnExpression("'${opa-guard.mode:cli}' == 'coordinator' or '${opa-guard.mode:cli}' == 'worker' or '${opa-guard.mode:cli}' == 'analyzer'")
public class PlatformSecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${opa-guard.security.jwk-set-uri}") String jwkSetUri) {
        if (!jwkSetUri.startsWith("https://") && !jwkSetUri.startsWith("http://keycloak:")) {
            throw new IllegalArgumentException("JWK Set URI must use HTTPS outside the local compose network");
        }
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    @Bean
    SecurityFilterChain platformSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAuthority("SCOPE_metrics.read")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").hasAuthority("SCOPE_api.docs")
                        .requestMatchers(HttpMethod.POST, "/api/v1/organizations/*/policy-versions",
                                "/api/v1/organizations/*/dataset-versions").hasAuthority("SCOPE_artifact.write")
                        .requestMatchers("/api/**").hasAuthority("SCOPE_benchmark.run")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(frame -> frame.deny()))
                .build();
    }
}
