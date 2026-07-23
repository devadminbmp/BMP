/**
 * Salon MODULE — salons, stylists, services, policy, availability model.
 *
 * <p>Public surface: com.bmp.salon.api. Session 5: this module is now its own independently-deployable service (see CONTEXT.md) — entities/repositories/services/controllers/dto/advices/config/exceptions are flat packages under com.bmp.salon, no longer nested under an internal/ package (that was the Spring Modulith convention, retired when the microservices split happened).
 * <p>Owns tables in: salon_schema —
 * V003: salon, salon_policy, salon_hours, salon_service, stylist, stylist_salon,
 * stylist_service, salon_combo, salon_combo_item, salon_staff, staff_invites.
 * V004: stylist_availability, walk_in_block — the availability model. Was blocked
 * pending a paper design against 3 real salons + all-founder sign-off (see
 * AvailabilityApi.java javadoc); a paper design was drafted and reviewed with
 * Darshan this session (CONTEXT.md Session Log has the full Q1-Q6 answers).
 * NOT YET ratified by Shivam/Achyuth — treat as a strong draft, not final, until
 * they've reviewed it.
 *
 * <p><b>Session 5:</b> this is now an independently-deployable Spring Boot
 * service (see its own pom.xml/Application.java/application.yml), not a Modulith
 * module of one shared deployable. Spring Modulith annotation removed accordingly.
 */
package com.bmp.salon;
