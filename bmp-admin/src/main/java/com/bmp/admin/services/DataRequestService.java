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
    /** Session 60 — the export half of the right of access. See DataExportService. */
    private final DataExportService exports;

    public DataRequestService(DataRequestRepository requests, UserServiceClient users,
                              AuditLogService audit, PlatformSettingService settings,
                              DataExportService exports,
                              @Value("${bmp.admin.data-request-due-days:30}") int fallbackDueDays) {
        this.requests = requests;
        this.users = users;
        this.audit = audit;
        this.settings = settings;
        this.exports = exports;
        this.fallbackDueDays = fallbackDueDays;
    }

    /**
     * Assemble the subject's data. Session 60.
     *
     * <h2>Verified first, same as fulfilment</h2>
     * Generating the bundle IS the disclosure — after this returns, a full copy of somebody's
     * history exists on a staff member's machine. Gating only the "mark complete" button would make
     * the verification step decorative, and the commonest reason for an export request nobody made
     * is that the account has been taken over.
     *
     * <h2>Read-only, and audited as a disclosure</h2>
     * Does not change the request's status. Generating an export and DECIDING it is finished are
     * two acts, and an agent will usually do the first, check the bundle, then do the second — a
     * method that silently completed the request would take that check away.
     *
     * @throws ResponseStatusException 409 if identity is unverified, or if this is not an export
     *         request. A deletion request has no bundle to produce, and producing one anyway would
     *         hand out data somebody asked us to erase.
     */
    @Transactional(readOnly = true)
    public DataExportService.ExportBundle export(UUID id, StaffPrincipal caller, String ip) {
        DataRequest request = load(id);
        requireFulfilPermission(caller);

        if (!request.isIdentityVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Verify the requester's identity before producing their data. Handing a full "
                    + "history to whoever asked is itself a breach.");
        }
        if (!"export".equals(request.getRequestType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This is a '" + request.getRequestType() + "' request, not an export. There is "
                    + "nothing to hand over.");
        }

        DataExportService.ExportBundle bundle = exports.build(request.getSubjectUserId(), caller.email());

        audit.record("bmp_staff", caller.staffId(), "DATA_EXPORT_GENERATED", "user",
                request.getSubjectUserId(),
                Map.of("requestId", id.toString(),
                       "sections", String.valueOf(bundle.sections().keySet()),
                       "complete", String.valueOf(bundle.problems().isEmpty())),
                ip, caller.email(), caller.role(),
                "Full data export produced for a verified subject access request.");

        log.info("Data export generated for request {} by {} — {} sections, {} problems.",
                id, caller.email(), bundle.sections().size(), bundle.problems().size());
        return bundle;
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

    /**
     * The same request, raised by the CUSTOMER rather than by an agent. Session 61.
     *
     * <h2>Why a separate method and not a nullable caller</h2>
     * {@link #create} takes a {@link StaffPrincipal} and writes an audit row naming the staff
     * member who raised it. Threading a nullable caller through that would produce audit rows with
     * an empty actor, and "who raised this" is the first question asked about a deletion request.
     * Here the actor is the data subject, and the audit row says so.
     *
     * <h2>One open request per person per type</h2>
     * Somebody who taps twice, or who asks again a week later because nothing has visibly happened,
     * gets their EXISTING request back rather than a second one. A duplicate would restart nothing
     * — the statutory clock runs from the first — and would put two identical items in front of an
     * agent who then has to work out whether they are the same person.
     *
     * <p>Deliberately does NOT block a delete when an export is open, or vice versa. They are
     * different requests and somebody may legitimately want a copy of their data before erasing it
     * — arguably that is the sensible order.
     */
    @Transactional
    public DataRequest raiseBySubject(String requestType, UUID subjectUserId) {
        UserServiceClient.UserDto user = users.getUserById(subjectUserId).getBody();
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        DataRequest existing = requests.findBySubjectUserId(subjectUserId).stream()
                .filter(r -> requestType.equals(r.getRequestType()))
                .filter(r -> !FINISHED.contains(r.getStatus()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            log.info("{} already has an open '{}' request ({}) — returning it rather than opening "
                    + "a second.", subjectUserId, requestType, existing.getId());
            return existing;
        }

        DataRequest request = requests.save(new DataRequest(
                requestType, user.id(), user.email(),
                Instant.now().plus(dueDays(), ChronoUnit.DAYS)));

        /*
         * Actor type "user", not "bmp_staff". The audit log's whole value is that it answers "who
         * did this" — recording a customer's own request as a staff action would be a false answer
         * on the one row where it matters most.
         */
        audit.record("user", subjectUserId, "DATA_REQUEST_RAISED_BY_SUBJECT", "user", user.id(),
                Map.of("type", requestType, "requestId", request.getId().toString()),
                null, user.email(), "customer",
                "Raised by the account holder through the app.");

        log.info("Data request {} ({}) raised by the account holder {}",
                request.getId(), requestType, subjectUserId);
        return request;
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
     * <p><b>Session 56 — the anonymise step now exists and this calls it.</b> The paragraphs above
     * describe what used to happen and are kept because the distinction still matters: erasure
     * removes PERSONAL DATA and keeps the commercial record. bmp-user clears the name, email,
     * gender, age, photo and hair profile, and replaces the phone with a non-dialable tombstone
     * that frees the real number for a future signup. The id survives so bookings and invoices
     * still resolve to something.
     *
     * <p><b>Session 60 — the export path exists too.</b> {@link #export} assembles the bundle across
     * profile, bookings, reviews and support tickets. What is still a human step, deliberately, is
     * DELIVERY: the agent who verified the requester's identity sends it through the channel they
     * verified. See {@code DataExportService} for why auto-emailing would defeat the check.
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
                /*
                 * Session 56 — anonymise, not deactivate.
                 *
                 * Deactivation was never erasure. It left every personal field on the row, and
                 * bmp-auth reverses it on the next successful OTP login, so a "deleted" account
                 * returned intact whenever its owner signed in again. Recording that as a
                 * completed erasure request produced a signed compliance record for something
                 * that had not happened.
                 *
                 * Failure is fatal to the whole operation on purpose: the request stays open
                 * rather than being marked complete, because a half-actioned erasure that LOOKS
                 * done is worse than one still visibly in the queue.
                 */
                users.anonymise(request.getSubjectUserId(), "deletion_request");
                actionTaken = "Personal data erased: name, email, gender, age, photo and hair "
                        + "profile cleared; phone replaced with a non-dialable placeholder. The "
                        + "account cannot be restored. Booking and invoice records are retained "
                        + "without personal identifiers, as required for tax and accounting.";
            } catch (Exception e) {
                log.error("Anonymisation failed for data request {} ({}) — the request has NOT "
                        + "been marked complete.", id, e.toString());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Could not erase the account — nothing was marked complete. Try again; "
                        + "if it keeps failing, escalate rather than closing this by hand.");
            }
        } else if ("export".equals(request.getRequestType())) {
            /*
             * The bundle is produced by its own endpoint, on purpose — see export().
             *
             * This branch does NOT generate it. An agent completes the request AFTER they have
             * downloaded the bundle and sent it, so generating one here would either duplicate a
             * disclosure that already happened or produce one nobody sent. What this records is the
             * fact the agent is asserting.
             */
            actionTaken = "Data export handed over by " + caller.email()
                    + ". The bundle is generated by the console (profile, bookings, reviews and "
                    + "help requests) and delivered through the channel used to verify identity.";
        } else {
            actionTaken = "Marked complete by " + caller.email()
                    + ". Correction requests are actioned by hand until an automated path exists.";
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
