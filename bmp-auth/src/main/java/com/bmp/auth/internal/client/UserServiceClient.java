package com.bmp.auth.internal.client;

import com.bmp.auth.internal.dto.CreateUserRequest;
import com.bmp.auth.internal.dto.UserDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Calls bmp-user-service by its Eureka-registered name — "bmp-user-service" here must
 * exactly match spring.application.name in bmp-user's own application.yml. Feign +
 * Eureka resolve the actual host:port at call time, so this never hardcodes localhost:8082.
 */
@FeignClient(name = "bmp-user-service")
public interface UserServiceClient {

    @GetMapping("/api/v1/users")
    ResponseEntity<UserDto> getUserByPhone(@RequestParam("phone") String phone);

    @PostMapping("/api/v1/users")
    UserDto createUser(@RequestBody CreateUserRequest request);
}
