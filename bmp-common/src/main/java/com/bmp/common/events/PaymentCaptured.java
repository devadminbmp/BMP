package com.bmp.common.events;

import java.util.UUID;

/**
 * Money arrived for a booking. Session 50.
 *
 * <h2>This event is the only thing that makes a booking real</h2>
 * Until it fires, a booking is PENDING and its invoice says "amount due" — both true, because
 * nothing has been paid. bmp-booking consumes this to move the booking to CONFIRMED and turn the
 * invoice into a receipt.
 *
 * <p>It is published <b>from the webhook</b>, which is the only source of payment truth. Not from
 * the client saying it paid, and not from the create-order call succeeding: a customer whose card
 * was declined after the order opened would otherwise get a confirmed booking.
 *
 * <h2>The split travels with it</h2>
 * {@code commissionPaise} and {@code salonSharePaise} are frozen on the payment order at
 * creation, and are carried here so a consumer never has to call back into bmp-payment to render
 * a receipt or write a ledger line. A notification service that needs a synchronous call into the
 * payment service to describe a payment is one outage away from silence at the worst moment.
 *
 * @param aggregateId       the payment order id
 * @param bookingId         what was paid for
 * @param salonId           who is owed {@code salonSharePaise}
 * @param gatewayPaymentId  {@code pay_XXXX} — the reference on the customer's bank statement, and
 *                          the one support will be quoted down the phone
 * @param amountPaise       what was actually taken, per the gateway
 * @param commissionPaise   BMP's share, frozen at order creation
 * @param salonSharePaise   the salon's share. {@code commission + salonShare == amount}, asserted
 *                          by a CHECK constraint in V004
 * @param commissionBps     the RATE that produced the split, so it stays explainable later
 * @param capturedAtEpochMs when the gateway says the money moved — NOT when we processed it.
 *                          A redelivered webhook must not move this.
 */
public record PaymentCaptured(
        UUID aggregateId,
        UUID bookingId,
        UUID salonId,
        String gatewayPaymentId,
        long amountPaise,
        long commissionPaise,
        long salonSharePaise,
        Integer commissionBps,
        long capturedAtEpochMs
) implements DomainEvent {

    @Override
    public String eventType() {
        return "payment.captured";
    }
}
