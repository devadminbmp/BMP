package com.bmp.booking.services;

import com.bmp.booking.api.BookingStatus;
import com.bmp.booking.entities.Booking;
import com.bmp.booking.entities.Invoice;
import com.bmp.booking.repositories.BookingRepository;
import com.bmp.booking.repositories.InvoiceRepository;
import com.bmp.common.events.PaymentCaptured;
import com.bmp.common.kafka.KafkaTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Money arrived — confirm the booking and turn the invoice into a receipt. Session 50.
 *
 * <h2>The gap this closes</h2>
 * {@code BookingStatus} has reserved {@code PENDING → CONFIRMED} for {@code Actor.SYSTEM} since
 * the enum was written, with the comment <i>"Razorpay webhook ONLY"</i>. Nothing has ever
 * performed that transition. Every booking ever made has sat PENDING, because bmp-booking and
 * bmp-payment were not connected in either direction.
 *
 * <p>This is the consumer that finally makes that comment true.
 *
 * <h2>Why a booking is confirmed HERE and not by the client</h2>
 * The customer's app knows the payment sheet closed. It does not know whether the bank settled,
 * and it can be lied to by anyone with the developer console open. Only the gateway knows money
 * moved, it tells bmp-payment through the signed webhook, and bmp-payment tells this service.
 * The chain has one source of truth at its head and no shortcuts.
 *
 * <h2>Idempotency is not optional</h2>
 * Kafka is at-least-once and the gateway redelivers. This handler WILL see the same capture more
 * than once. Every step below therefore checks its own precondition and does nothing on a repeat
 * — rather than relying on the event arriving exactly once, which it will not.
 */
@Service
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final BookingRepository bookings;
    private final InvoiceRepository invoices;
    private final ObjectMapper mapper;

    public PaymentEventConsumer(BookingRepository bookings, InvoiceRepository invoices,
                                 ObjectMapper mapper) {
        this.bookings = bookings;
        this.invoices = invoices;
        this.mapper = mapper;
    }

    /**
     * @param eventType the header, not a field parsed out of the body — the topic carries every
     *                  domain event, and deserialising each one to find out what it is would fail
     *                  noisily on every event this service doesn't care about.
     */
    @KafkaListener(topics = KafkaTopics.EVENTS, groupId = "bmp-booking-service")
    public void onEvent(@Payload String payload,
                         @Header(name = "eventType", required = false) String eventType) {
        if (!"payment.captured".equals(eventType)) return;
        try {
            handleCaptured(mapper.readValue(payload, PaymentCaptured.class));
        } catch (Exception e) {
            /*
             * Logged, not rethrown. Rethrowing would redeliver forever on a payload this consumer
             * genuinely cannot read, and a permanent retry loop buries the failures that matter.
             *
             * ERROR and explicit about the consequence: the money is real and the customer is
             * looking at a booking that still says awaiting payment.
             */
            log.error("Failed to apply a payment.captured event ({}). A CUSTOMER HAS PAID and "
                    + "their booking has not been confirmed. Payload: {}", e.toString(), payload, e);
        }
    }

    @Transactional
    protected void handleCaptured(PaymentCaptured event) {
        Booking booking = bookings.findById(event.bookingId()).orElse(null);
        if (booking == null) {
            // Money captured against a booking this service has never heard of. Loud, because
            // somebody has paid and there is nothing to give them.
            log.error("payment.captured for booking {} — NO SUCH BOOKING. {} paise has been taken "
                    + "and cannot be matched. This needs manual reconciliation.",
                    event.bookingId(), event.amountPaise());
            return;
        }

        // ── 1. the booking ───────────────────────────────────────────────────────────────────
        if (booking.getStatus() == BookingStatus.PENDING) {
            // Asserted, not assumed. The enum is the authority on what may follow what, and
            // SYSTEM is the only actor permitted to make this move.
            booking.getStatus().assertTransition(BookingStatus.CONFIRMED, BookingStatus.Actor.SYSTEM);
            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setConfirmedAt(Instant.ofEpochMilli(event.capturedAtEpochMs()));
            bookings.save(booking);
            log.info("Booking {} CONFIRMED by payment {} ({} paise).",
                    booking.getBookingRef(), event.gatewayPaymentId(), event.amountPaise());
        } else if (booking.getStatus() == BookingStatus.CANCELLED) {
            /*
             * Paid for a booking that was already cancelled — a race between the customer paying
             * and someone cancelling, or a very late webhook.
             *
             * The booking is NOT resurrected. Cancelling had consequences: the slot was released
             * and may already be sold. Confirming it now would double-book a real chair. The
             * money is refundable and a human has to make that call.
             */
            log.error("payment.captured for booking {} which is CANCELLED. {} paise was taken and "
                    + "the slot is gone — this needs a refund decision. The booking has NOT been "
                    + "reinstated, because its slot may already belong to someone else.",
                    booking.getBookingRef(), event.amountPaise());
        } else {
            // Already CONFIRMED or beyond — a redelivery. Ordinary, not a problem.
            log.info("Booking {} is already {} — payment.captured redelivery ignored.",
                    booking.getBookingRef(), booking.getStatus());
        }

        // ── 2. the invoice becomes a receipt ─────────────────────────────────────────────────
        //
        // Done regardless of the branch above: a booking that was cancelled after payment still
        // has money against it, and the invoice must say so. A receipt for money that arrived is
        // true whatever happened to the appointment.
        Invoice invoice = invoices.findByBookingId(event.bookingId()).orElse(null);
        if (invoice == null) {
            // Not fatal. The invoice can be raised later from the desk, and the payment record
            // in bmp-payment is the authoritative one either way.
            log.warn("No invoice for booking {} at capture time — nothing to mark paid. It can be "
                    + "raised from the salon desk and will read 'due' until someone records it.",
                    event.bookingId());
            return;
        }

        /*
         * markPaid is itself idempotent and keeps the FIRST payment's details, so a redelivery
         * cannot move paid_at — the timestamp is the legal record of when the money arrived, and
         * overwriting it with the retry's arrival time quietly falsifies it.
         *
         * recordedBy is null: this is a gateway callback, not a person at a counter. That
         * distinction is the whole reason the column exists.
         */
        try {
            invoice.markPaid("gateway", event.gatewayPaymentId(), null);
            invoices.save(invoice);
            log.info("Invoice {} is now a receipt — {} paise via {}.",
                    invoice.getInvoiceNo(), invoice.getTotalPaise(), event.gatewayPaymentId());
        } catch (IllegalStateException e) {
            // Cancelled invoice. The money still arrived; the document just cannot become a
            // receipt for a cancelled bill.
            log.error("Could not mark invoice {} paid ({}). Money arrived for booking {} and the "
                    + "invoice does not reflect it.", invoice.getInvoiceNo(), e.getMessage(),
                    event.bookingId());
        }
    }
}
