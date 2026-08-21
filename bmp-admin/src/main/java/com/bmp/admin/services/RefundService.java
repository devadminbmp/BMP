package com.bmp.admin.services;

import com.bmp.admin.client.BookingServiceClient;
import com.bmp.admin.entities.RefundRequest;
import com.bmp.admin.repositories.RefundRequestRepository;
import com.bmp.admin.security.StaffPermission;
import com.bmp.admin.security.StaffPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Refund requests — the decision, not the payout.
 *
 * <h2>Two rules worth stating plainly</h2>
 *
 * <p><b>Nothing can actually be paid.</b> bmp-payment doesn't exist, no money has been collected
 * (bookings sit in PENDING until the Razorpay webhook lands), so an approved request goes to
 * {@code blocked} rather than {@code paid}. That's not a rejection — the customer's claim
 * stands — and keeping the two states distinct means nobody later mistakes a technical
 * limitation for a refusal. The console says so at the top of the screen, so an agent never
 * promises money that will never arrive.
 *
 * <p><b>The requester cannot approve their own request.</b> Not because agents can't be trusted,
 * but because "two people saw this" is the cheapest control that exists over money, and the day
 * one account is compromised it's the only thing standing between an attacker and the refund
 * queue. Superadmin is exempt only when there is genuinely nobody else — flagged in the log
 * when it happens.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private static final List<String> OPEN_STATUSES = List.of("requested", "approved", "blocked");

    private static final String PAYMENTS_UNAVAILABLE =
            "Online payments are not live yet — no money was collected for this booking, so "
            + "there is nothing to return. The request is recorded and will be actionable when "
            + "payments launch. Do not tell the customer they will receive money.";

    private final RefundRequestRepository refunds;
    private final BookingServiceClient bookings;
    private final AuditLogService audit;

    public RefundService(RefundRequestRepository refunds, BookingServiceClient bookings,
                         AuditLogService audit) {
        this.refunds = refunds;
        this.bookings = bookings;
        this.audit = audit;
    }

    public List<RefundRequest> list(String status) {
        return status == null || status.isBlank()
                ? refunds.findAllByOrderByCreatedAtDesc()
                : refunds.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional
    public RefundRequest request(UUID bookingId, Long amountPaise, String reason,
                                 StaffPrincipal caller, String ip) {
        if (reason == null || reason.trim().length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say what the refund is for — whoever approves it needs to know.");
        }

        // Checked here as well as by the partial unique index, so two agents working the same
        // complaint get a sentence rather than a constraint violation. That's normal, not
        // exceptional — a customer who calls twice gets two agents.
        refunds.findFirstByBookingIdAndStatusIn(bookingId, OPEN_STATUSES).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "There's already an open refund request for this booking (raised by %s)."
                            .formatted(existing.getRequestedByEmail()));
        });

        BookingServiceClient.SupportBooking booking = loadBooking(bookingId);

        long amount = amountPaise == null ? booking.finalAmountPaise() : amountPaise;
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The amount must be more than zero.");
        }
        // Refunding more than was charged is always a mistake, and always an expensive one.
        if (amount > booking.finalAmountPaise()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That's more than the booking total of ₹%d.".formatted(booking.finalAmountPaise() / 100));
        }

        RefundRequest saved = refunds.save(new RefundRequest(
                bookingId, booking.bookingRef(), booking.customerId(), booking.salonId(),
                amount, booking.finalAmountPaise(), reason.trim(),
                caller.staffId(), caller.email(),
                "requested", null));

        audit.record("bmp_staff", caller.staffId(), "REFUND_REQUESTED", "booking", bookingId,
                Map.of("amountPaise", amount, "bookingRef", String.valueOf(booking.bookingRef())),
                ip, caller.email(), caller.role(), reason);

        log.info("Refund requested: booking={} amount={}p by={}", bookingId, amount, caller.email());
        return saved;
    }

    /**
     * Approve or reject.
     *
     * <p>An approval lands in {@code blocked}, not {@code paid} — see the class comment. When
     * bmp-payment exists it will pick up blocked rows, pay them, and set {@code paid}.
     */
    @Transactional
    public RefundRequest decide(UUID refundId, String decision, String note,
                                StaffPrincipal caller, String ip) {
        if (!caller.can(StaffPermission.REFUND_ISSUE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role cannot approve refunds. Finance or an admin does that.");
        }

        RefundRequest refund = refunds.findById(refundId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND"));

        if (!"requested".equals(refund.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This request has already been " + refund.getStatus() + ".");
        }

        // The four-eyes rule. Cheapest control over money there is.
        boolean isSelfApproval = caller.staffId().equals(refund.getRequestedBy());
        if (isSelfApproval && !StaffPermission.SUPER_ADMIN.equalsIgnoreCase(caller.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Someone else has to approve a refund you raised.");
        }
        if (isSelfApproval) {
            log.warn("SELF-APPROVED REFUND: {} approved their own request {} — superadmin override",
                    caller.email(), refundId);
        }

        if (!List.of("approved", "rejected").contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_DECISION: " + decision);
        }
        if ("rejected".equals(decision) && (note == null || note.trim().length() < 10)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Give a reason — the customer is entitled to know why.");
        }

        // Approved becomes 'blocked' while payments don't exist. Honest, and it means the queue
        // is already correct the day they do.
        String finalStatus = "approved".equals(decision) ? "blocked" : "rejected";
        String blockedReason = "approved".equals(decision) ? PAYMENTS_UNAVAILABLE : null;

        refund.decide(finalStatus, caller.staffId(), caller.email(), note, blockedReason);

        audit.record("bmp_staff", caller.staffId(),
                "approved".equals(decision) ? "REFUND_APPROVED" : "REFUND_REJECTED",
                "booking", refund.getBookingId(),
                Map.of("refundId", refundId.toString(), "amountPaise", refund.getAmountPaise()),
                ip, caller.email(), caller.role(), note);

        log.info("Refund {} {} by {} (stored as {})", refundId, decision, caller.email(), finalStatus);
        return refund;
    }

    public long openCount() {
        return refunds.countByStatusIn(OPEN_STATUSES);
    }

    private BookingServiceClient.SupportBooking loadBooking(UUID bookingId) {
        try {
            // Search by reference is the only lookup bmp-booking exposes for staff; the console
            // passes an id it already has, so match on it directly from the customer's list.
            // TODO(bmp-booking): a by-id internal lookup would make this one call instead of a
            // scan — worth doing before the booking table is large.
            return bookings.search(bookingId.toString()).stream()
                    .filter(b -> b.id().equals(bookingId))
                    .findFirst()
                    .orElseGet(() -> bookings.byCustomer(bookingId).stream()
                            .filter(b -> b.id().equals(bookingId))
                            .findFirst()
                            .orElseThrow(() -> new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND")));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not load booking {} for a refund request ({})", bookingId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Couldn't load that booking just now. Nothing was recorded — please try again.");
        }
    }
}
