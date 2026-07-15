/**
 * Notification MODULE — outbox processor + WhatsApp/SMS/push dispatch (MSG91, FCM).
 *
 * <p>Public surface: com.bmp.notification.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: common_schema.outbox is processed here; notification_schema — notification_log.
 *
 * <p><b>Session 5:</b> this is now an independently-deployable Spring Boot
 * service (see its own pom.xml/Application.java/application.yml), not a Modulith
 * module of one shared deployable. Spring Modulith annotation removed accordingly.
 */
package com.bmp.notification;
