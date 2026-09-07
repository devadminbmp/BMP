package com.bmp.auth.client;

import com.bmp.auth.dto.CreateUserRequest;
import com.bmp.auth.dto.UserDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Calls bmp-user-service by its Eureka-registered name — "bmp-user-service" here must
 * exactly match spring.application.name in bmp-user's own application.yml. Feign +
 * Eureka resolve the actual host:port at call time, so this never hardcodes localhost:8082.
 */
@FeignClient(name = "bmp-user-service", configuration = com.bmp.auth.config.FeignInternalKeyConfig.class)
public interface UserServiceClient {

    @GetMapping("/api/v1/users")
    ResponseEntity<UserDto> getUserByPhone(@RequestParam("phone") String phone);

    /** Session 6: added for AuthService.refresh() — minting a new access token needs the
     * user's CURRENT role, which requires looking them up by id, not just by phone. */
    @GetMapping("/api/v1/users/{userId}")
    ResponseEntity<UserDto> getUserById(@PathVariable("userId") java.util.UUID userId);

    @PostMapping("/api/v1/users")
    UserDto createUser(@RequestBody CreateUserRequest request);

    /** Session 13: a deactivated user completing a fresh OTP login gets auto-reactivated
     * (Instagram-style soft deactivation — see bmp-user's UserService.reactivate). */
    @PostMapping("/api/v1/users/{userId}/reactivate")
    UserDto reactivateUser(@PathVariable("userId") java.util.UUID userId);

    /**
     * Session 65 — apply a self-service contact change, once its code has been confirmed.
     *
     * <p>Null means "leave this one alone". Sending an empty string would CLEAR the field, and
     * clearing the email on a platform whose login codes go by email locks the person out for
     * good — so ContactChangeService passes null, never "".
     *
     * <p>Same endpoint bmp-admin calls for a support-performed change. One writer for one
     * invariant: bmp-user re-canonicalises the phone and re-checks uniqueness against
     * {@code uk_users_phone} regardless of which service asked.
     */
    record ChangeContactRequest(String phone, String email) {}

    @org.springframework.web.bind.annotation.PatchMapping("/api/v1/users/{userId}/contact")
    UserDto changeContact(@PathVariable("userId") java.util.UUID userId,
                           @RequestBody ChangeContactRequest req);
}
