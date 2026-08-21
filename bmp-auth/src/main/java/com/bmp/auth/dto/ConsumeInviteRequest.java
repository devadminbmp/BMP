package com.bmp.auth.dto;

import java.util.UUID;

/**
 * Local mirror of bmp-salon's StaffDtos.ConsumeInviteRequest — sent over Feign at signup.
 *
 * <p>Session 17: {@code stylistId} added. It is null for a MANAGER invite (bmp-salon creates a
 * salon_staff seat keyed on userId) and required for a STYLIST invite, where bmp-salon needs
 * the id of the portable Stylist profile we just created in order to write the stylist_salon
 * link. bmp-salon can't resolve that id itself — the profile is created in this same signup
 * transaction, moments earlier.
 */
public record ConsumeInviteRequest(String token, String phone, String userId, UUID stylistId) {

    /** Manager signup — no stylist profile involved. */
    public ConsumeInviteRequest(String token, String phone, String userId) {
        this(token, phone, userId, null);
    }
}
