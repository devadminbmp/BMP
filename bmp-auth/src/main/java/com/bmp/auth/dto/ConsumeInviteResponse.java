package com.bmp.auth.dto;

import java.util.UUID;

/**
 * Local mirror of bmp-salon's StaffDtos.ConsumeInviteResponse.
 *
 * <p>{@code role} (Session 17) says what was actually created: a {@code manager} staff seat or
 * a {@code stylist} salon link. It matters because only the manager case may set the session's
 * salonId — a stylist's JWT stays unscoped by design.
 */
public record ConsumeInviteResponse(UUID salonId, String role) {}
