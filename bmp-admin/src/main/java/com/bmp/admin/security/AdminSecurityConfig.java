package com.bmp.admin.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security for the console API.
 *
 * <h2>Why a second filter chain rather than editing the shared one</h2>
 * bmp-common's {@code CommonSecurityConfig} defines the customer chain, and every service in
 * the platform depends on it. This chain is registered at {@code @Order(1)} and matches only
 * {@code /api/v1/admin/**}, so:
 *
 * <ul>
 *   <li>Admin routes are authenticated by {@link StaffAuthFilter} — a different key, a
 *       different audience — and the customer JWT filter never sees them.</li>
 *   <li>The internal service-key credential that lets services call each other does NOT grant
 *       access here. bmp-admin calls other services; nothing calls into the console.</li>
 *   <li>Everything else in this service (actuator, swagger) still falls through to the shared
 *       chain, so nothing regresses.</li>
 * </ul>
 *
 * <h2>CORS is an allowlist of exactly one origin</h2>
 * The console is the only thing that may call this API from a browser. A wildcard here would
 * let any site a signed-in staff member visits make authenticated requests on their behalf.
 */
@Configuration
public class AdminSecurityConfig {

    private final StaffAuthFilter staffAuthFilter;
    private final String consoleOrigin;

    public AdminSecurityConfig(
            StaffAuthFilter staffAuthFilter,
            @Value("${bmp.admin.console-origin:http://localhost:5180}") String consoleOrigin) {
        this.staffAuthFilter = staffAuthFilter;
        this.consoleOrigin = consoleOrigin;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/admin/**")
            .csrf(AbstractHttpConfigurer::disable)   // bearer tokens, no cookies — nothing to forge
            .cors(cors -> cors.configurationSource(consoleCors()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // The login handshake itself must be reachable without a session token.
                // Everything past the password step is protected by the challenge token, which
                // the auth service validates explicitly rather than relying on this chain.
                .requestMatchers(
                        "/api/v1/admin/auth/login",
                        "/api/v1/admin/auth/totp/verify",
                        "/api/v1/admin/auth/totp/enrol",
                        "/api/v1/admin/auth/activate",
                        "/api/v1/admin/auth/refresh").permitAll()
                .anyRequest().authenticated())
            /*
             * ══════════════════════════════════════════════════════════════════════════════════
             * 401 WHEN THERE IS NO SESSION, 403 ONLY WHEN THERE IS ONE AND IT IS NOT ENOUGH.
             * Session 65.
             * ══════════════════════════════════════════════════════════════════════════════════
             * Without an explicit entry point, Spring Security 6 answers an UNAUTHENTICATED
             * request to a protected path with 403. That is the wrong code and it produced a
             * genuinely baffling bug:
             *
             *   · The staff token expires (or StaffAuthFilter cannot parse it).
             *   · No Authentication is set, so `anyRequest().authenticated()` denies.
             *   · Spring returns 403.
             *   · The console treats 401 as "session over" and 403 as "you lack a permission",
             *     so it neither refreshes nor signs out — it just renders "Request failed with
             *     status code 403" on every panel.
             *   · The shell still looks signed in, because it restores from sessionStorage
             *     without revalidating (VITE_DEV_PERSIST_SESSION).
             *
             * The result: a SUPER ADMIN — who passes every @PreAuthorize on the platform —
             * staring at 403 on Salon approvals, which reads as a broken permission model rather
             * than an expired login.
             *
             * The distinction is the whole point of having two codes. 401 means "I don't know who
             * you are, sign in again". 403 means "I know exactly who you are and the answer is
             * still no". Only the second is about permissions.
             */
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"error\":\"SESSION_EXPIRED\","
                            + "\"message\":\"Your console session has ended. Sign in again.\"}");
                }))
            .addFilterBefore(staffAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource consoleCors() {
        CorsConfiguration config = new CorsConfiguration();
        // Exact origin, not a pattern. See the class comment.
        config.setAllowedOrigins(List.of(consoleOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);   // the token is in a header, never a cookie
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/admin/**", config);
        return source;
    }

    /**
     * Cost 12 rather than the default 10 — roughly four times the work per attempt. On a table
     * this small the login latency is imperceptible, and it meaningfully slows an offline
     * attack if the hashes ever leak.
     */
    @Bean
    public BCryptPasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
