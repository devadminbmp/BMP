package com.bmp.auth.client;

import com.bmp.auth.dto.ConsumeInviteRequest;
import com.bmp.auth.dto.ConsumeInviteResponse;
import com.bmp.auth.dto.CreateStylistRequest;
import com.bmp.auth.dto.StaffLookupResponse;
import com.bmp.auth.dto.StylistDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Calls bmp-salon-service for Session 6's role/salon linkage: consuming a manager's invite
 * token, resolving a user's current staff seat for JWT claims, and auto-creating a
 * stylist's portable profile row at STYLIST signup. All three are internal-only endpoints on
 * the bmp-salon side (see StaffController/StylistController there), reached with the shared
 * X-Internal-Service-Key credential — see FeignInternalKeyConfig.
 */
@FeignClient(name = "bmp-salon-service", configuration = com.bmp.auth.config.FeignInternalKeyConfig.class)
public interface SalonServiceClient {

    @PostMapping("/api/v1/salons/internal/staff-invites/consume")
    ConsumeInviteResponse consumeInvite(@RequestBody ConsumeInviteRequest request);

    @GetMapping("/api/v1/salons/internal/staff-lookup")
    ResponseEntity<StaffLookupResponse> lookupStaff(@RequestParam("userId") UUID userId);

    @PostMapping("/api/v1/stylists")
    StylistDto createStylist(@RequestBody CreateStylistRequest request);
}
