package com.bmp.admin.controllers;

import com.bmp.admin.entities.DataRequest;
import com.bmp.admin.services.DataRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

/**
 * A customer asking for their own data. {@code ROLE_SERVICE} only — called by bmp-user.
 * Session 61.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE QUEUE COULD ONLY BE FILLED BY STAFF
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * `POST /api/v1/admin/data-requests` takes a {@code StaffPrincipal} — so a data request could only
 * exist if an agent typed it in on somebody's behalf, which in practice means after a phone call.
 * Session 60 built the export that fulfils these requests; nothing let the person who has the right
 * actually exercise it.
 *
 * <p>Under the DPDP Act the data principal has to have a route. A queue only staff can fill is a
 * process, not a route.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS IS SEPARATE FROM THE STAFF ENDPOINT AND NOT A FLAG ON IT
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Different caller, different authorisation, different audit actor. Merging them would mean one
 * endpoint whose security depends on which of two principals happens to be present — and the branch
 * that decides is exactly the sort of thing that gets a "temporary" third case added later.
 */
@Tag(name = "Internal data requests",
     description = "Service-only. Opens a DPDP request raised by the account holder themselves.")
@RestController
@RequestMapping("/api/v1/data-requests")
@PreAuthorize("hasRole('SERVICE')")
public class InternalDataRequestController {

    /**
     * {@code correct} is deliberately absent.
     *
     * <p>The staff endpoint accepts it because an agent can act on a correction described over the
     * phone. Self-service correction is a different feature — it needs to know WHAT to correct —
     * and offering the option with nowhere to type the correction would produce requests an agent
     * has to chase the customer about.
     */
    private static final Set<String> SELF_SERVICE_TYPES = Set.of("export", "delete");

    private final DataRequestService dataRequests;

    public InternalDataRequestController(DataRequestService dataRequests) {
        this.dataRequests = dataRequests;
    }

    /** @param subjectUserId filled by bmp-user from the caller's token, never from a browser. */
    public record RaiseDataRequest(
            @NotBlank String requestType,
            @NotNull UUID subjectUserId,
            String note) {}

    /** @param dueAt the statutory deadline, as an ISO date the app shows the customer verbatim. */
    public record Raised(UUID id, String requestType, String status, String dueAt) {}

    /**
     * Open it, or return the one they already have.
     *
     * <p>Idempotent per person per type — see {@code DataRequestService.raiseBySubject}. A second
     * tap restarts nothing (the clock runs from the first) and would put two identical items in
     * front of an agent.
     */
    @Operation(summary = "Raise a data request as the account holder",
               description = "Idempotent: returns the existing open request of the same type if there is one.")
    @PostMapping
    public Raised raise(@Valid @RequestBody RaiseDataRequest req) {
        String type = req.requestType().trim().toLowerCase();
        if (!SELF_SERVICE_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "requestType must be one of " + SELF_SERVICE_TYPES);
        }

        DataRequest request = dataRequests.raiseBySubject(type, req.subjectUserId());
        return new Raised(request.getId(), request.getRequestType(), request.getStatus(),
                String.valueOf(request.getDueAt()));
    }
}
