package com.bmp.admin.security;

import java.util.UUID;

/**
 * The authenticated staff member, as controllers see them via
 * {@code @AuthenticationPrincipal StaffPrincipal caller}.
 *
 * <p>Deliberately NOT {@code com.bmp.common.security.AuthenticatedUser}. That type represents a
 * customer, and reusing it here would mean any code path that accepts an AuthenticatedUser
 * would silently accept a staff member and vice versa. A separate type makes the boundary a
 * compile error rather than a code review.
 *
 * <p>Carries the email and role so audit entries can record who someone WAS at the time,
 * without a database round-trip on every request.
 */
public record StaffPrincipal(UUID staffId, String email, String role) {

    public boolean can(String permission) {
        return StaffPermission.has(role, permission);
    }
}
