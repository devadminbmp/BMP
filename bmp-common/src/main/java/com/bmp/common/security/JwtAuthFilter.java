package com.bmp.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Validates the JWT issued by bmp-auth-service on every request, for every service that
 * depends on bmp-common. Two credential types are accepted, matching JwtService's two
 * token flavors (see bmp-auth's JwtService):
 *
 * <ol>
 *   <li><b>User tokens</b> ({@code tokenType=user}) — {@code sub} is the user's UUID,
 *       {@code role} is one of CUSTOMER/SALON_OWNER/MANAGER/STYLIST, optional
 *       {@code salonId} scopes the role to one salon. Mapped to a Spring Security
 *       authority {@code ROLE_<role>} and an {@link AuthenticatedUser} principal.</li>
 *   <li><b>Service tokens</b> — a static shared secret ({@code X-Internal-Service-Key}
 *       header, value = {@code bmp.security.internal-service-key}) used for
 *       service-to-service Feign calls that happen before any end-user identity exists
 *       yet (e.g. bmp-auth calling bmp-user's createUser during signup). Mapped to the
 *       single authority {@code ROLE_SERVICE}. This is a pragmatic pre-PMF shortcut, not
 *       mTLS — documented here so it isn't mistaken for something stronger.</li>
 * </ol>
 *
 * <p>Missing/invalid tokens are NOT rejected here — this filter only populates the
 * SecurityContext when a token is present and valid. Whether a missing/anonymous context
 * is allowed through is decided by {@link CommonSecurityConfig}'s permit list further down
 * the chain, keeping the "is this path public" decision in one place per service.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final SecretKey key;
    private final String internalServiceKey;

    public JwtAuthFilter(
            @Value("${bmp.auth.jwt-secret:dev-only-secret-change-in-real-environment-min-32-bytes-long}") String secret,
            @Value("${bmp.security.internal-service-key:dev-only-internal-key-change-in-real-environment}") String internalServiceKey) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.internalServiceKey = internalServiceKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String serviceHeader = request.getHeader("X-Internal-Service-Key");
        if (serviceHeader != null && serviceHeader.equals(internalServiceKey)) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = Jwts.parser().verifyWith(key).build()
                        .parseSignedClaims(header.substring(7))
                        .getPayload();

                UUID userId = UUID.fromString(claims.getSubject());
                String role = claims.get("role", String.class);
                String salonIdStr = claims.get("salonId", String.class);
                UUID salonId = salonIdStr != null ? UUID.fromString(salonIdStr) : null;

                List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                AuthenticatedUser principal = new AuthenticatedUser(userId, role, salonId);

                var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (ExpiredJwtException | JwtException | IllegalArgumentException ignored) {
                // Leave the SecurityContext empty — CommonSecurityConfig decides whether
                // the requested path requires authentication at all.
            }
        }

        chain.doFilter(request, response);
    }
}
