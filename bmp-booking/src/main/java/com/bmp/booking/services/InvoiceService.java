package com.bmp.booking.services;

import com.bmp.booking.entities.Booking;
import com.bmp.booking.entities.BookingServiceItem;
import com.bmp.booking.entities.Invoice;
import com.bmp.booking.entities.InvoiceLine;
import com.bmp.booking.repositories.BookingRepository;
import com.bmp.booking.repositories.BookingServiceItemRepository;
import com.bmp.booking.repositories.InvoiceLineRepository;
import com.bmp.booking.repositories.InvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Invoices and receipts. V008 (Session 49).
 *
 * <h2>Why this exists now, with no payment gateway</h2>
 * Nothing sends the Razorpay webhook yet, so every booking sits PENDING and no money is recorded
 * anywhere. The temptation is to wait for payments before building bills.
 *
 * <p>That would be the wrong order. A customer who has booked wants something they can show at
 * the counter <em>today</em>, and a salon wants a record of what was charged. So the document is
 * raised at confirmation and says <b>amount due</b> — which is true. When payments arrive, the
 * same row becomes a receipt through {@link #recordPayment}, and nothing about the document, its
 * number, or the code around it has to change.
 *
 * <p>Being honest about the state is the whole design. An invoice that says "Paid" because the
 * system has no way to know otherwise is worse than no invoice.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final BookingRepository bookings;
    private final BookingServiceItemRepository items;

    public InvoiceService(InvoiceRepository invoices, InvoiceLineRepository lines,
                           BookingRepository bookings, BookingServiceItemRepository items) {
        this.invoices = invoices;
        this.lines = lines;
        this.bookings = bookings;
        this.items = items;
    }

    /** BMP-INV-000412. Zero-padded so a printed list sorts correctly as text. */
    private String allocateNumber() {
        return String.format("BMP-INV-%06d", invoices.nextInvoiceNumber());
    }

    /**
     * Raise the invoice for a booking, or return the one that already exists.
     *
     * <h2>Idempotent on purpose</h2>
     * Called from booking confirmation, and also lazily by the read endpoints for bookings that
     * predate this feature. Two callers must not produce two documents — the unique constraint on
     * {@code booking_id} makes that impossible at the database level, and returning the existing
     * row makes it a non-event rather than a 409 the caller has to handle.
     *
     * @param salonName   passed in rather than looked up: bmp-booking has no salon table, and the
     *                    caller already holds it. Frozen on the invoice, so a later rename leaves
     *                    old invoices alone.
     */
    @Transactional
    public Invoice issueFor(UUID bookingId, String salonName, String salonAddress) {
        var existing = invoices.findByBookingId(bookingId);
        if (existing.isPresent()) return existing.get();

        Booking b = bookings.findById(bookingId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        long gross = b.getGrossAmountPaise() != null
                ? b.getGrossAmountPaise().paise()
                // Pre-V005 bookings have no gross. Falling back to the final amount is right:
                // with no recorded discount, gross and final ARE the same number.
                : b.getFinalAmountPaise().paise();
        long discount = b.getDiscountPaise() != null ? b.getDiscountPaise().paise() : 0L;

        /*
         * Defensive: the CHECK constraint asserts total = gross - discount, so a booking whose
         * discount somehow exceeds its gross would fail the insert with a constraint name nobody
         * can read. Clamping here and logging loudly turns a 500 into a correct invoice plus an
         * alert — the customer gets a bill either way, and somebody finds out.
         */
        if (discount > gross) {
            log.error("Booking {} has discount {} > gross {} — clamping the invoice discount. "
                    + "This is a data problem in the booking, not in the invoice.",
                    bookingId, discount, gross);
            discount = gross;
        }

        Invoice inv = new Invoice(bookingId, b.getSalonId(), b.getCustomerId(), allocateNumber(),
                salonName == null || salonName.isBlank() ? "Salon" : salonName,
                salonAddress, b.getCustomerName(), b.getBookingRef(),
                gross, discount);
        invoices.save(inv);

        /*
         * Lines are copied from the booking's items, in service order. name_snapshot and
         * price_paise_snapshot are already frozen on the item — this copies the freeze forward
         * so that deleting the booking's items later could never blank an issued invoice.
         */
        List<BookingServiceItem> src = items.findByBookingId(bookingId);
        int n = 1;
        for (BookingServiceItem i : src) {
            lines.save(new InvoiceLine(inv.getId(), n++,
                    i.getNameSnapshot(),
                    null,   // stylist name is not on the item; the salon's own copy shows it
                    i.getDurationShownMinutes(),
                    i.getPricePaiseSnapshot().paise()));
        }

        log.info("Issued invoice {} for booking {} — {} line(s), total {} paise, status DUE.",
                inv.getInvoiceNo(), bookingId, src.size(), inv.getTotalPaise());
        return inv;
    }

    public Invoice byBooking(UUID bookingId) {
        return invoices.findByBookingId(bookingId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "NO_INVOICE: no bill has been raised for this booking yet."));
    }

    public List<InvoiceLine> linesOf(UUID invoiceId) {
        return lines.findByInvoiceIdOrderByLineNoAsc(invoiceId);
    }

    /**
     * Record that the money arrived — this is what turns the invoice into a receipt.
     *
     * <h2>Who may call it, and why that matters</h2>
     * Today: the salon, marking a counter payment. Later: the payment webhook. Both land here, so
     * there is exactly one place where an invoice becomes paid and exactly one place to look when
     * a customer says they paid and the system disagrees.
     *
     * <p>{@code recordedBy} is stored for the counter case and null for a gateway callback. A
     * cash payment is a claim by a person, and the first question when the till doesn't balance
     * is who made it.
     */
    @Transactional
    public Invoice recordPayment(UUID invoiceId, String method, String reference, UUID recordedBy) {
        Invoice inv = invoices.findById(invoiceId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND"));
        boolean wasPaid = inv.isPaid();
        try {
            inv.markPaid(method, reference, recordedBy);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        invoices.save(inv);
        if (!wasPaid) {
            log.info("Invoice {} PAID — {} paise via {} (ref {}), recorded by {}.",
                    inv.getInvoiceNo(), inv.getTotalPaise(), method, reference, recordedBy);
        }
        return inv;
    }

    /** Money given back. Cumulative; the status only flips to refunded when it's all returned. */
    @Transactional
    public Invoice recordRefund(UUID invoiceId, long paise) {
        Invoice inv = invoices.findById(invoiceId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND"));
        try {
            inv.recordRefund(paise);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        invoices.save(inv);
        log.info("Invoice {} refunded {} paise (total refunded {} of {}).",
                inv.getInvoiceNo(), paise, inv.getRefundedPaise(), inv.getTotalPaise());
        return inv;
    }

    /** The booking was cancelled before money moved. The row and its number are KEPT. */
    @Transactional
    public Invoice cancel(UUID invoiceId) {
        Invoice inv = invoices.findById(invoiceId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND"));
        try {
            inv.cancel();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        invoices.save(inv);
        return inv;
    }
}
