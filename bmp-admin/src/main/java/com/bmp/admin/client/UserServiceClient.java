package com.bmp.admin.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.UUID;

/**
 * Reads customer records for the console, with the internal service credential.
 *
 * <p><b>Why the console can't call bmp-user itself:</b> if it did, every time a support agent
 * looked up a customer's phone number it would happen invisibly. Routing through bmp-admin means
 * there is exactly one place where PII access is recorded, and it is not optional.
 *
 * <p>The DTO is a partial mirror of bmp-user's {@code UserResponse} — only the fields the
 * console needs, so an unrelated field being added over there can't break this service.
 */
@FeignClient(name = "bmp-user-service", configuration = com.bmp.admin.config.FeignInternalKeyConfig.class)
public interface UserServiceClient {

    record UserDto(
        UUID id, String phone, String name, String gender, Integer age, String email,
        String profilePhotoUrl, String hairType, String hairLength, String defaultRole,
        boolean isVerified, Instant deactivatedAt, Instant createdAt, Instant updatedAt
    ) {}

    @GetMapping("/api/v1/users/{userId}")
    ResponseEntity<UserDto> getUserById(@PathVariable("userId") UUID userId);

    /** E.164. Service-only over there — arbitrary phone→profile lookup is an enumeration risk. */
    @GetMapping("/api/v1/users")
    ResponseEntity<UserDto> getUserByPhone(@RequestParam("phone") String phone);

    /**
     * Soft-deactivate, used to fulfil a DPDP erasure request.
     *
     * <p>Deliberately NOT a hard delete: booking records are business records that must be
     * retained, so erasure means removing what identifies the person, not destroying the
     * financial history. The full anonymisation step is still to build — see DataRequestService.
     */
    @PostMapping("/api/v1/users/{userId}/deactivate")
    ResponseEntity<UserDto> deactivate(@PathVariable("userId") UUID userId);
}
