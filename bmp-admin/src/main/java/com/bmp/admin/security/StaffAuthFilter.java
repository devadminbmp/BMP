package com.bmp.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Turns a staff bearer token into an authenticated principal, for {@code /api/v1/admin/**} only.
 *
 * <p>Runs INSTEAD OF bmp-common's customer {@link com.bmp.common.security.JwtAuthFilter} on
 * admin routes — see {@link AdminSecurityConfig}. That's the point: a customer token presented
 * here isn't merely rejected by an authorization rule, it is never parsed by anything that
 * would accept it, because {@link AdminJwtService} requires a different signing key AND a
 * different audience.
 *
 * <p>Only tokens at the {@code session} stage authenticate. The short-lived challenge token
 * issued between the password and the 2FA code is explicitly not enough — without that check,
 * "passed the password step" would be indistinguishable from "logged in", which makes the
 * second factor decorative.
 */
@Component
public class StaffAuthFilter extends OncePerRequestFilter {

    private final AdminJwtService jwt;

    public StaffAuthFilter(AdminJwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            StaffPrincipal principal = jwt.parse(header.substring(7), AdminJwtService.STAGE_SESSION);
            if (principal != null) {
                // Spring's hasRole('X') prefixes ROLE_ and is case-sensitive — the same trap
                // that bit the customer services in an earlier session. Uppercase here, once.
                var authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + principal.role().toUpperCase()));
                var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
