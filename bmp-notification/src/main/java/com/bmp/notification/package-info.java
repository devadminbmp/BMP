/**
 * Notification MODULE — outbox processor + WhatsApp/SMS/push dispatch (MSG91, FCM).
 *
 * <p>Public surface: com.bmp.notification.api only. internal/ is invisible to other modules.
 * <p>Owns tables in: common_schema.outbox is processed here; notification_schema — notification_log.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Notification",
    allowedDependencies = { "common" }
)
package com.bmp.notification;
