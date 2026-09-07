package com.bmp.common.security;

import jakarta.servlet.http.HttpServletResponse;
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
 * <p><b>Session 29: the "leave it as /** until your authorization pass" advice that used to be
 * here has been deleted, along with the default that made it possible.</b> Every service must
 * now declare its public paths explicitly or it will not start. The interim state it described
 * lasted long enough to leave 52 endpoints unauthenticated across four services, which is what
 * "we'll tighten it later" reliably becomes.
 */
@Configuration
@EnableMethodSecurity
public class CommonSecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final String[] publicPaths;

    /**
     * ═══════════════════════════════════════════════════════════════════════════════════════
     * THERE IS NO DEFAULT FOR public-paths, AND THAT IS THE WHOLE POINT.
     * ═══════════════════════════════════════════════════════════════════════════════════════
     * This used to read {@code @Value("${bmp.security.public-paths:/**}")}. A service that
     * never mentioned the property therefore got {@code permitAll()} on EVERY endpoint.
     *
     * <p>That is not a hypothetical. When this was measured across the platform, <b>52 endpoints
     * required no credential of any kind</b> — 35 of bmp-salon's 51, and everything in
     * bmp-payment, bmp-review and bmp-notification. Not one of them was a decision anybody made.
     * They were all the same omission, repeated in four services, because splitting one process
     * into thirteen turned a single authorization decision into thirteen chances to forget one.
     *
     * <p>Removing the default inverts the failure mode. A service that forgets now fails to
     * START, with an unresolvable-placeholder error naming the property, instead of quietly
     * serving its entire surface to the internet. Loud at boot beats silent in production.
     *
     * <p><b>If you are here because a service won't start:</b> that is this working. Add to that
     * service's application.yml:
     * <pre>
     * bmp:
     *   security:
     *     public-paths: /actuator/health, /actuator/info, /swagger-ui/**, /v3/api-docs/**
     * </pre>
     * and add ONLY the endpoints that must be reachable with no token at all. If you find
     * yourself typing {@code /**}, stop — that is the bug this change exists to prevent.
     */
    public CommonSecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            @Value("${bmp.security.public-paths}") String publicPathsCsv) {
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
            /*
             * ══════════════════════════════════════════════════════════════════════════════════
             * 401 WHEN THERE IS NO TOKEN. 403 ONLY WHEN THERE IS ONE AND IT IS NOT ENOUGH.
             * Session 65 — this is the root cause of "refreshing the browser logs me out".
             * ══════════════════════════════════════════════════════════════════════════════════
             *
             * Without an explicit entry point, Spring Security 6 answers an UNAUTHENTICATED
             * request to a protected path with 403. Every service built on this config did that,
             * and the customer/owner web app was destroyed by it on every single page refresh:
             *
             *   1. The ACCESS token is deliberately never persisted — it lives in memory and is
             *      re-minted from the refresh token on launch (see BMP-FE/src/api/storage.ts).
             *      So immediately after F5 there is no Authorization header. By design.
             *   2. hydrate() calls GET /auth/me to restore the session.
             *   3. No token -> Spring denies -> 403.
             *   4. The client's interceptor refreshes on 401 ONLY. A 403 is not a 401, so the
             *      refresh never fired — the one mechanism built to handle exactly this case.
             *   5. The session store read 403 as "the server has rejected this credential",
             *      DELETED the refresh token from localStorage, and dropped to the login screen.
             *
             * Every step is reasonable in isolation. Together they mean a correct, unexpired,
             * thirty-day refresh token was thrown away because a page was reloaded.
             *
             * Session 64 tried to fix this by retrying transient failures. It could not work: a
             * 403 is not transient, it is an answer — the wrong one.
             *
             * The two codes exist to be different. 401: "I do not know who you are — refresh or
             * sign in." 403: "I know exactly who you are and the answer is still no." Only the
             * second is about permissions, and only the first should ever cost somebody a session.
             */
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"error\":\"UNAUTHENTICATED\","
                            + "\"message\":\"No valid credential on this request.\"}");
                }))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
