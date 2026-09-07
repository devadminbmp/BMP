package com.bmp.admin.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * @param deactivatedAt the person paused THEMSELVES; their next login reactivates them.
     * @param blockedAt     STAFF stopped them (Session 65). A login does not clear it. Two fields
     *                      because they mean opposite things — see bmp-user's V006.
     */
    record UserDto(
        UUID id, String phone, String name, String gender, Integer age, String email,
        String profilePhotoUrl, String hairType, String hairLength, String defaultRole,
        boolean isVerified, Instant deactivatedAt, Instant createdAt, Instant updatedAt,
        Instant blockedAt, String blockedReason
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

    /**
     * Erase the person's personal data. Session 56, and what an erasure request actually needs.
     *
     * <p>Deactivation was standing in for this and could not do the job: it leaves every field in
     * place, and bmp-auth reverses it on the next successful OTP login — so a "deleted" account
     * came back intact the moment its owner signed in.
     *
     * <p>Irreversible. bmp-user refuses to reactivate an anonymised row.
     */
    @PostMapping("/api/v1/users/{userId}/anonymise")
    ResponseEntity<UserDto> anonymise(@PathVariable("userId") UUID userId,
                                       @org.springframework.web.bind.annotation.RequestParam("reason") String reason);

    /** Session 65 — administered contact change. See ConsoleController's account block. */
    record ChangeContactRequest(String phone, String email) {}

    @org.springframework.web.bind.annotation.PatchMapping("/api/v1/users/{userId}/contact")
    UserDto changeContact(@PathVariable UUID userId, @RequestBody ChangeContactRequest req);

    record BlockRequest(UUID staffId, String reason) {}

    /**
     * Block sign-in. Session 65.
     *
     * <h2>NOT {@link #deactivate}, and that distinction is the whole feature</h2>
     * The console's Block button originally called {@code deactivate}. bmp-auth reactivates a
     * deactivated account the moment its owner completes an OTP login — correct for somebody who
     * paused themselves, catastrophic as a block. The button worked, the audit entry was written,
     * and the person signed straight back in.
     *
     * <p>{@code deactivate} still exists and is still right for what it was built for: fulfilling
     * an erasure request alongside {@link #anonymise}. Do not point Block at it again.
     */
    @PostMapping("/api/v1/users/{userId}/block")
    ResponseEntity<UserDto> block(@PathVariable("userId") UUID userId, @RequestBody BlockRequest req);

    /** Lift a block. Does not reactivate an account the person had deactivated themselves. */
    @PostMapping("/api/v1/users/{userId}/unblock")
    ResponseEntity<UserDto> unblock(@PathVariable("userId") UUID userId);
}
