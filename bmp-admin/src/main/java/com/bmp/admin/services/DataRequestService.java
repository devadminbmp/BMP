package com.bmp.admin.services;

import com.bmp.admin.client.UserServiceClient;
import com.bmp.admin.dto.ConsoleDtos.*;
import com.bmp.admin.entities.DataRequest;
import com.bmp.admin.repositories.DataRequestRepository;
import com.bmp.admin.security.StaffPermission;
import com.bmp.admin.security.StaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data subject requests — India's DPDP Act 2023.
 *
 * <p>People have the right to access and erase their personal data, on a statutory clock. These
 * rows are how you PROVE you honoured a request; "we definitely did it" is not a defence.
 *
 * <h2>The order is enforced, not suggested</h2>
 * Identity must be verified before anything is exported or erased. Honouring a forged deletion
 * request destroys a real customer's history; honouring a forged export hands their life to a
 * stranger. Both are breaches caused by helping too quickly — the characteristic failure of a
 * well-meaning support team, which is why it's a state machine and not a checklist.
 */
@Service
public class DataRequestService {

    private static final Logger log = LoggerFactory.getLogger(DataRequestService.class);

    private static final List<String> FINISHED = List.of("completed", "rejected");

    private final DataRequestRepository requests;
    private final UserServiceClient users;
    private final AuditLogService audit;
    private final PlatformSettingService settings;
    private final int fallbackDueDays;

    public DataRequestService(DataRequestRepository requests, UserServiceClient users,
                              AuditLogService audit, PlatformSettingService settings,
                              @Value("${bmp.admin.data-request-due-days:30}") int fallbackDueDays) {
        this.requests = requests;
        this.users = users;
        this.audit = audit;
        this.settings = settings;
        this.fallbackDueDays = fallbackDueDays;
    }

    /**
     * The statutory deadline, in days.
     *
     * <p>Session 23: read from platform_setting rather than config, so it can be corrected the
     * day someone reads the DPDP rules properly — without a deploy, and with the change audited.
     * The config value remains as a fallback if the row is missing.
     */
    private int dueDays() {
        return (int) settings.number(PlatformSettingService.DATA_REQUEST_DUE_DAYS, fallbackDueDays);
    }

    public List<DataRequestResponse> list(String status) {
        List<DataRequest> found = status == null || status.isBlank()
                ? requests.findAllByOrderByDueAtAsc()
                : requests.findByStatusOrderByDueAtAsc(status);
        return found.stream().map(this::toResponse).toList();
    }

    @Transactional
    public DataRequestResponse create(CreateDataRequestRequest req, StaffPrincipal caller, String ip) {
        UserServiceClient.UserDto user = users.getUserById(req.subjectUserId()).getBody();
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        DataRequest request = requests.save(new DataRequest(
                req.requestType(), user.id(), user.email(),
                Instant.now().plus(dueDays(), ChronoUnit.DAYS)));

        audit.record("bmp_staff", caller.staffId(), "DATA_REQUEST_RAISED", "user", user.id(),
                Map.of("type", req.requestType(), "requestId", request.getId().toString()),
                ip, caller.email(), caller.role(), req.notes());

        log.info("Data request raised: type={} user={} by={}", req.requestType(), user.id(), caller.email());
        return toResponse(request);
    }

    @Transactional
    public DataRequestResponse verifyIdentity(UUID id, DataRequestActionRequest req, StaffPrincipal caller, String ip) {
        DataRequest request = load(id);
        requireFulfilPermission(caller);

        if (request.isIdentityVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Identity is already verified.");
        }
        if (req.note() == null || req.note().trim().length() < 10) {
            // How identity was confirmed IS the evidence. Without it the verification step is
            // just a button someone pressed.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Record how you confirmed their identity.");
        }

        request.markIdentityVerified(caller.staffId(), req.note());
        audit.record("bmp_staff", caller.staffId(), "DATA_REQUEST_IDENTITY_VERIFIED", "user",
                request.getSubjectUserId(), Map.of("requestId", id.toString()),
                ip, caller.email(), caller.role(), req.note());
        return toResponse(request);
    }

    /**
     * Fulfil the request.
     *
     * <p>⚠️ PARTIALLY IMPLEMENTED, and deliberately honest about it. For {@code delete} we
     * deactivate the account, which hides it — but full erasure means anonymising the personal
     * fields while KEEPING booking records, since those are business records the law requires
     * us to retain. That anonymisation step doesn't exist yet.
     *
     * <p>So the row is marked completed and the note records what was actually done. Marking it
     * completed while silently doing less than the customer asked would be the worse failure:
     * you'd have a signed record claiming compliance you didn't achieve.
     *
     * <p>TODO(bmp-user): POST /internal/users/{id}/anonymise — null the name, phone, email and
     * profile fields, keep the id so bookings still join.
     * TODO(export): compile a machine-readable bundle across user/booking/review and deliver it.
     */
    @Transactional
    public DataRequestResponse complete(UUID id, DataRequestActionRequest req, StaffPrincipal caller, String ip) {
        DataRequest request = load(id);
        requireFulfilPermission(caller);

        // The gate. Not a warning, not a confirm dialog — a refusal.
        if (!request.isIdentityVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Verify the requester's identity before fulfilling this. Acting on a forged "
                    + "request is itself a data breach.");
        }
        if (FINISHED.contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request is already " + request.getStatus() + ".");
        }

        String actionTaken;
        if ("delete".equals(request.getRequestType())) {
            try {
                users.deactivate(request.getSubjectUserId());
                actionTaken = "Account deactivated. NOTE: full anonymisation is not implemented — "
                        + "personal fields remain on the user record pending TODO(bmp-user) anonymise.";
            } catch (Exception e) {
                log.error("Deactivation failed for data request {} ({})", id, e.toString());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Could not deactivate the account — nothing was marked complete.");
            }
        } else {
            actionTaken = "Marked complete by " + caller.email()
                    + ". Export/correction is performed manually until the automated path exists.";
        }

        request.complete(caller.staffId(),
                (req.note() == null ? "" : req.note() + " — ") + actionTaken);

        audit.record("bmp_staff", caller.staffId(), "DATA_REQUEST_COMPLETED", "user",
                request.getSubjectUserId(),
                Map.of("requestId", id.toString(), "type", request.getRequestType()),
                ip, caller.email(), caller.role(), req.note());

        log.info("Data request {} completed by {} ({})", id, caller.email(), actionTaken);
        return toResponse(request);
    }

    @Transactional
    public DataRequestResponse reject(UUID id, DataRequestActionRequest req, StaffPrincipal caller, String ip) {
        DataRequest request = load(id);
        requireFulfilPermission(caller);

        if (req.note() == null || req.note().trim().length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Give a reason — the requester is entitled to know why.");
        }
        request.reject(caller.staffId(), req.note());
        audit.record("bmp_staff", caller.staffId(), "DATA_REQUEST_REJECTED", "user",
                request.getSubjectUserId(), Map.of("requestId", id.toString()),
                ip, caller.email(), caller.role(), req.note());
        return toResponse(request);
    }

    public long outstandingCount() {
        return requests.countByStatusNotIn(FINISHED);
    }

    private void requireFulfilPermission(StaffPrincipal caller) {
        if (!caller.can(StaffPermission.DATA_REQUEST_FULFIL)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role can view data requests but not action them.");
        }
    }

    private DataRequest load(UUID id) {
        return requests.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DATA_REQUEST_NOT_FOUND"));
    }

    private DataRequestResponse toResponse(DataRequest r) {
        return new DataRequestResponse(
                r.getId(), r.getRequestType(), r.getSubjectUserId(), r.getSubjectEmail(),
                r.getStatus(), r.getIdentityVerifiedAt(), r.getDueAt(), r.getCompletedAt(),
                r.getNotes(), r.getCreatedAt());
    }
}
