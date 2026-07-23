package com.bmp.common.events;

import java.util.UUID;

/**
 * Emitted by bmp-auth-service the first time a phone/email verifies successfully and a new
 * user row is created (any role — customer, salon owner, manager, stylist). Consumers:
 * bmp-notification-service (welcome email/SMS), later an analytics/CRM bridge if one shows
 * up. Not emitted on subsequent logins by the same user.
 */
public record UserRegistered(
        UUID aggregateId,   // the new user's id
        String phone,
        String email,       // nullable
        String role,
        UUID salonId        // nullable — only set for MANAGER/STYLIST joining via invite code
) implements DomainEvent {

    @Override
    public String eventType() {
        return "user.registered";
    }
}
