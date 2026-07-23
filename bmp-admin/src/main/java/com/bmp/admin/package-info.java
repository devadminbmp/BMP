/**
 * Admin MODULE — bmp_staff (separate auth!), support tickets, append-only audit log.
 *
 * <p>Public surface: com.bmp.admin.api. Session 5: this module is now its own independently-deployable service (see CONTEXT.md) — entities/repositories/services/controllers/dto/advices/config/exceptions are flat packages under com.bmp.admin, no longer nested under an internal/ package (that was the Spring Modulith convention, retired when the microservices split happened).
 * <p>Owns tables in: admin_schema — bmp_staff, support_ticket, support_message, audit_log.
 *
 * <p><b>Session 5:</b> this is now an independently-deployable Spring Boot
 * service (see its own pom.xml/Application.java/application.yml), not a Modulith
 * module of one shared deployable. Spring Modulith annotation removed accordingly.
 */
package com.bmp.admin;
