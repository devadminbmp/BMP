package com.bmp.rewards.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

/**
 * Resolves a person's contact details, so a notification has somewhere to go.
 *
 * <h2>Why bmp-rewards needs this at all</h2>
 * {@code NotificationDispatcher} holds no clients: every event carries the phone and email it
 * needs in its payload. That is a good constraint — the dispatcher stays a dumb delivery
 * mechanism and cannot fan out into a dozen lookups per message — but it means whoever EMITS an
 * event is responsible for putting a real address in it.
 *
 * <p>Staff-raised requests arrive from bmp-admin with the email already attached. Salon-owner
 * requests arrive from the app with nothing but a user id from the token, so the address is
 * resolved here, once, at the moment the request is created — and stored on the row. Resolving
 * it later (at decision time) would mean an outbound call inside the approval transaction, and
 * a notification that fails because bmp-user was briefly down would take the approval with it.
 *
 * <p>Deliberately minimal: one method. A client that grows every field anyone might want becomes
 * a second, worse copy of bmp-user.
 */
@FeignClient(name = "bmp-user-service", configuration = com.bmp.rewards.config.FeignInternalKeyConfig.class)
public interface UserServiceClient {

    /** Only the fields a notification needs. bmp-user returns more; Jackson ignores the rest. */
    record UserContact(UUID id, String phone, String name, String email) {}

    @GetMapping("/api/v1/users/{userId}")
    ResponseEntity<UserContact> getUserById(@PathVariable("userId") UUID userId);
}
