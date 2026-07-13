/**
 * Admin MODULE — bmp_staff (separate auth!), support tickets, append-only audit log.
 *
 * <p>Public surface: com.bmp.admin.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: admin_schema — bmp_staff, support_ticket, support_message, audit_log.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Admin",
    allowedDependencies = { "common" }
)
package com.bmp.admin;
