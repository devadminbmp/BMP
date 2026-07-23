package com.bmp.common.security;

import java.util.UUID;

/**
 * The JWT principal, available in any controller/service as
 * {@code @AuthenticationPrincipal AuthenticatedUser user}. {@code salonId} is null for
 * roles not scoped to a single salon (e.g. CUSTOMER), or for a SALON_OWNER token minted
 * before they've created their first salon.
 */
public record AuthenticatedUser(UUID userId, String role, UUID salonId) {}
