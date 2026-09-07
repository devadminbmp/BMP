package com.bmp.user.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

/**
 * Registering a device for push, in bmp-notification. Session 64.
 *
 * <p>bmp-notification is service-to-service only by design, so the app talks to bmp-user and
 * bmp-user talks to it — the same shape as the moderation and data-request surfaces.
 */
@FeignClient(name = "bmp-notification-service", contextId = "userPushClient")
public interface PushServiceClient {

    /** {@code userId} is filled in HERE from the verified JWT. Never from the app's request body. */
    record RegisterPush(UUID userId, String token, String platform, String deviceId) {}

    /** Session 65 — userId scopes the release so one person cannot silence another. */
    record ReleasePush(String token, java.util.UUID userId) {}

    @PostMapping("/api/v1/notifications/internal/push/register")
    void register(@RequestBody RegisterPush req);

    @PostMapping("/api/v1/notifications/internal/push/release")
    void release(@RequestBody ReleasePush req);
}
