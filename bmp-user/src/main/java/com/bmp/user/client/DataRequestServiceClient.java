package com.bmp.user.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

/**
 * Opening a DPDP request on the account holder's own behalf. Session 61.
 *
 * <p>The queue lives in bmp-admin; the identity comes from bmp-user's token. Same split as support
 * tickets and content reports — and the same rule: {@code subjectUserId} is filled in by the
 * controller, never taken from the request, because an endpoint that accepts a caller-supplied
 * subject id is an endpoint for erasing other people's accounts.
 */
@FeignClient(name = "bmp-admin-service",
             contextId = "dataRequestServiceClient",
             configuration = com.bmp.user.config.FeignInternalKeyConfig.class)
public interface DataRequestServiceClient {

    record RaiseDataRequest(String requestType, UUID subjectUserId, String note) {}

    /** @param dueAt the statutory deadline, as an ISO date shown to the customer verbatim. */
    record Raised(UUID id, String requestType, String status, String dueAt) {}

    @PostMapping("/api/v1/data-requests")
    Raised raise(@RequestBody RaiseDataRequest body);
}
