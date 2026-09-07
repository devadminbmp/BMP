package com.bmp.booking.controllers;

import com.bmp.booking.entities.Invoice;
import com.bmp.booking.entities.InvoiceLine;
import com.bmp.booking.services.BookingService;
import com.bmp.booking.services.InvoiceService;
import com.bmp.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bills and receipts. V008 (Session 49).
 *
 * <h2>Who may see an invoice</h2>
 * The customer it belongs to, and the salon that issued it. Nobody else — and specifically
 * <b>not the stylist who did the work</b>. A stylist has no money surface anywhere in this
 * product (see {@code BookingDtos.StylistScheduleEntry}); handing them the customer's bill,
 * complete with the customer's name, would undo that in one endpoint.
 *
 * <p>Both checks are done in the method bodies rather than in SpEL because they need a lookup —
 * "whose booking is this?" — and expressing that in an annotation string would be unreadable.
 * That matches the convention already set in {@link BookingController}.
 */
@Tag(name = "Invoices", description = "One bill per booking. Reads 'amount due' until the money "
        + "is recorded, then becomes a receipt with the same number.")
@RestController
public class InvoiceController {

    private final InvoiceService service;
    private final BookingService bookings;

    public InvoiceController(InvoiceService service, BookingService bookings) {
        this.service = service;
        this.bookings = bookings;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────────────────────

    public record LineDto(int lineNo, String description, String stylistName,
                           Integer durationMinutes, long amountPaise) {}

    /**
     * @param outstandingPaise what is still owed, computed server-side. The client must not
     *                         subtract this itself — a bill whose arithmetic depends on the
     *                         reader is not a bill.
     * @param paidMethod       null until the money is recorded
     */
    public record InvoiceDto(
            UUID id, UUID bookingId, String invoiceNo, String bookingRef,
            String salonName, String salonAddress, String customerName,
            long grossPaise, long discountPaise, long totalPaise,
            long refundedPaise, long outstandingPaise,
            String status, boolean paid,
            String paidMethod, String paidReference, Instant paidAt,
            Instant issuedAt,
            List<LineDto> lines) {}

    public record RecordPaymentBody(
            /** 'cash', 'upi', 'card'. Free text — how a salon takes money isn't ours to enumerate. */
            @Size(max = 20) String method,
            @Size(max = 120) String reference) {}

    // ── the customer's copy ──────────────────────────────────────────────────────────────────

    @Operation(
        summary = "My bill for a booking",
        description = "The customer's own copy. Reads 'amount due' until payment is recorded, "
            + "then the same document becomes a receipt.")
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/api/v1/bookings/{bookingId}/invoice")
    public InvoiceDto myInvoice(@PathVariable UUID bookingId,
                                 @AuthenticationPrincipal AuthenticatedUser caller) {
        Invoice inv = service.byBooking(bookingId);
        /*
         * Ownership, checked against the INVOICE's customer rather than re-reading the booking.
         * The invoice froze the customer at issue; if a booking were ever reassigned, the bill
         * still belongs to whoever was billed.
         */
        if (inv.getCustomerId() == null || !inv.getCustomerId().equals(caller.userId())) {
            // Same 404 as "no invoice" on purpose — a 403 would confirm that a bill exists for a
            // booking id somebody is guessing at.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NO_INVOICE");
        }
        return toDto(inv);
    }

    // ── the salon's copy ─────────────────────────────────────────────────────────────────────

    @Operation(
        summary = "The salon's copy of a bill",
        description = "Owner or manager of the salon that issued it. Note STYLIST is absent: a "
            + "stylist has no money surface anywhere in BMP, and this endpoint would be the "
            + "hole in it.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @GetMapping("/api/v1/salons/{salonId}/bookings/{bookingId}/invoice")
    public InvoiceDto salonInvoice(@PathVariable UUID salonId, @PathVariable UUID bookingId,
                                    @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOwnSalon(salonId, caller);
        Invoice inv = service.byBooking(bookingId);
        if (!inv.getSalonId().equals(salonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NO_INVOICE");
        }
        return toDto(inv);
    }

    /**
     * Raise the bill for a booking that doesn't have one.
     *
     * <p>Bookings made before this feature existed have no invoice, and a salon asked for a bill
     * at the counter cannot wait for a backfill job. Idempotent — a second call returns the
     * first invoice rather than issuing a second one, which the unique constraint on
     * {@code booking_id} would refuse anyway.
     */
    @Operation(summary = "Raise the bill for a booking", description = "Idempotent — returns the existing invoice if there is one.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @PostMapping("/api/v1/salons/{salonId}/bookings/{bookingId}/invoice")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDto issue(@PathVariable UUID salonId, @PathVariable UUID bookingId,
                             @RequestParam(required = false) String salonName,
                             @RequestParam(required = false) String salonAddress,
                             @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOwnSalon(salonId, caller);
        if (!salonId.equals(bookings.salonIdOf(bookingId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "BOOKING_BELONGS_TO_ANOTHER_SALON");
        }
        return toDto(service.issueFor(bookingId, salonName, salonAddress));
    }

    /**
     * "They've paid." Turns the invoice into a receipt.
     *
     * <h2>Why a salon can do this before the payment gateway exists</h2>
     * Most of these salons take cash and UPI at the counter today. Waiting for Razorpay before
     * anybody can mark a bill paid would mean every invoice in the system reads "due" forever,
     * which makes the whole feature decorative.
     *
     * <p>It records WHO said so. A counter payment is a claim by a person, and the first question
     * when the till doesn't balance is who made it.
     */
    @Operation(
        summary = "Record a payment",
        description = "Marks the bill paid and stores who recorded it. Idempotent: marking an "
            + "already-paid invoice again keeps the ORIGINAL payment details, because "
            + "overwriting them would move when the money was received.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER')")
    @PostMapping("/api/v1/salons/{salonId}/invoices/{invoiceId}/payment")
    public InvoiceDto recordPayment(@PathVariable UUID salonId, @PathVariable UUID invoiceId,
                                     @Valid @RequestBody RecordPaymentBody body,
                                     @AuthenticationPrincipal AuthenticatedUser caller) {
        requireOwnSalon(salonId, caller);
        Invoice inv = service.recordPayment(invoiceId,
                body.method() == null || body.method().isBlank() ? "cash" : body.method().trim(),
                body.reference(), caller.userId());
        // Checked AFTER the lookup inside the service, so a salon can't probe other salons'
        // invoice ids by timing — but before returning anything about it.
        if (!inv.getSalonId().equals(salonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND");
        }
        return toDto(inv);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private void requireOwnSalon(UUID salonId, AuthenticatedUser caller) {
        if (caller == null || caller.salonId() == null || !caller.salonId().equals(salonId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NOT_YOUR_SALON");
        }
    }

    private InvoiceDto toDto(Invoice i) {
        List<LineDto> lines = service.linesOf(i.getId()).stream()
                .map((InvoiceLine l) -> new LineDto(l.getLineNo(), l.getDescription(),
                        l.getStylistName(), l.getDurationMinutes(), l.getAmountPaise()))
                .toList();
        return new InvoiceDto(i.getId(), i.getBookingId(), i.getInvoiceNo(), i.getBookingRef(),
                i.getSalonName(), i.getSalonAddress(), i.getCustomerName(),
                i.getGrossPaise(), i.getDiscountPaise(), i.getTotalPaise(),
                i.getRefundedPaise(), i.outstandingPaise(),
                i.getStatus(), i.isPaid(),
                i.getPaidMethod(), i.getPaidReference(), i.getPaidAt(),
                i.getIssuedAt(), lines);
    }
}
