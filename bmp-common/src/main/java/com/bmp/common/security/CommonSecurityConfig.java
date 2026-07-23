package com.bmp.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * One shared Spring Security setup for every service, opted into by adding bmp-common as a
 * dependency (already true for all 9 business/auth services). Stateless (JWT-only, no
 * sessions, no CSRF — there's no browser form/cookie flow here, just bearer tokens),
 * method-level {@code @PreAuthorize} enabled for role checks per-endpoint.
 *
 * <p>Each service declares its own PUBLIC paths (endpoints reachable with no token at all —
 * login/signup, health checks) via {@code bmp.security.public-paths} in its own
 * application.yml, e.g.:
 * <pre>
 * bmp:
 *   security:
 *     public-paths: /api/v1/auth/**,/actuator/**
 * </pre>
 * Everything not on that list requires SOME valid credential (user JWT or the internal
 * service key) — see {@link JwtAuthFilter}. Role-specific restrictions beyond "logged in"
 * are enforced with {@code @PreAuthorize("hasRole('SALON_OWNER')")} etc. on individual
 * controller methods, not here.
 *
 * <p>A service with nothing meaningful to protect yet (booking/payment/review/rewards/
 * admin/notification, as of Session 6) can leave {@code public-paths} as {@code /**} to
 * keep today's fully-open behavior unchanged until their own authorization pass — see
 * CONTEXT.md Session 6 log for what's in scope now vs. deferred.
 */
@Configuration
@EnableMethodSecurity
public class CommonSecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final String[] publicPaths;

    public CommonSecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            @Value("${bmp.security.public-paths:/**}") String publicPathsCsv) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.publicPaths = publicPathsCsv.split("\\s*,\\s*");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(publicPaths).permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
