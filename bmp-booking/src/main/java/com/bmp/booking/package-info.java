/**
 * BOOKING MODULE — reservations, slot locks, booking lifecycle.
 *
 * <p><b>Public surface:</b> only {@code com.bmp.booking.api} is visible to other
 * modules. Everything in {@code internal} is compile-time invisible outside this
 * module (enforced by Spring Modulith verification in bmp-app tests).
 *
 * <p><b>Allowed dependencies:</b> salon (availability + policy lookups),
 * user (customer validation), common. Payment talks to booking, never the reverse
 * — booking learns about payment success via the {@code payment.success} event.
 *
 * <p><b>Owns tables in:</b> {@code booking_schema} — booking, booking_service_item,
 * booking_events, slot_lock. Cross-schema FKs to salon_schema/user_schema are real.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Booking",
    allowedDependencies = { "salon :: api", "user :: api", "common" }
)
package com.bmp.booking;
