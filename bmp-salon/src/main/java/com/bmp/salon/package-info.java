/**
 * Salon MODULE — salons, stylists, services, policy, AVAILABILITY MODEL (design first!).
 *
 * <p>Public surface: com.bmp.salon.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: salon_schema — salon, salon_policy, salon_service, stylist, stylist_salon, salon_staff, staff_invites, + availability tables TBD.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Salon",
    allowedDependencies = { "user :: api", "common" }
)
package com.bmp.salon;
