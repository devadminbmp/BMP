package com.bmp.common.time;

import java.time.ZoneId;

/**
 * Session 8 (availability algorithm): the single shared timezone every service must use
 * when converting between the Instant timestamps stored in booking_schema (booking_service_item,
 * slot_lock) and the LocalDate/LocalTime values stored in salon_schema (stylist_availability,
 * salon_hours, walk_in_block). BMP operates only in Bengaluru today (see CONTEXT.md Target
 * Market), so a single hardcoded zone is deliberate — this becomes a per-salon column the
 * day BMP expands outside IST, not before.
 */
public final class BmpTimeZone {
    private BmpTimeZone() {}

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
}
