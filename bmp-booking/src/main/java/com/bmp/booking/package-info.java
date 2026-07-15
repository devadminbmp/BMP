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
 *
 * <p><b>Session 5:</b> this is now an independently-deployable Spring Boot
 * service (see its own pom.xml/Application.java/application.yml), not a Modulith
 * module of one shared deployable. Spring Modulith annotation removed accordingly.
 */
package com.bmp.booking;
