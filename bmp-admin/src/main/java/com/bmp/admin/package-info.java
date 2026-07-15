/**
 * Admin MODULE — bmp_staff (separate auth!), support tickets, append-only audit log.
 *
 * <p>Public surface: com.bmp.admin.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: admin_schema — bmp_staff, support_ticket, support_message, audit_log.
 *
 * <p><b>Session 5:</b> this is now an independently-deployable Spring Boot
 * service (see its own pom.xml/Application.java/application.yml), not a Modulith
 * module of one shared deployable. Spring Modulith annotation removed accordingly.
 */
package com.bmp.admin;
