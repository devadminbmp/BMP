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
}
