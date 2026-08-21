package com.bmp.notification.services;

import com.bmp.common.events.BookingCancelled;
import com.bmp.common.events.BookingCompleted;
import com.bmp.common.events.BookingCreated;
import com.bmp.common.events.BookingRescheduled;
import com.bmp.common.events.CouponRequestDecided;
import com.bmp.common.events.CouponRequestRaised;
import com.bmp.common.events.OtpRequested;
import com.bmp.common.events.UserRegistered;
import com.bmp.common.time.BmpTimeZone;
import org.springframework.beans.factory.annotation.Value;
import com.bmp.common.kafka.KafkaTopics;
import com.bmp.notification.dto.NotificationDtos.LogRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Session 6: the actual consumer side of OutboxKafkaRelay — subscribes to
 * {@code bmp.events}, routes by the {@code eventType} header (set by the relay), and
 * performs the real side effect (email + SMS/WhatsApp send) that NotificationLogService's
 * plain CRUD never did on its own. Every dispatch is logged via NotificationLogService
 * first (so there's always a record, even if the actual send fails) — see the
 * queued -> sent/failed transition on NotificationLog.
 *
 * <p>At-least-once delivery: Kafka can redeliver on consumer restart. Both handlers here are
 * idempotent in the sense that resending the same OTP/welcome message twice is harmless
 * (not a correctness bug) — worth revisiting if a template is ever added where duplicate
 * sends WOULD matter (e.g. a payment receipt).
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final ObjectMapper mapper;
    private final NotificationLogService logService;
    private final EmailSender emailSender;
    private final SmsSender smsSender;

    /**
     * Where "somebody needs to look at this" mail goes.
     *
     * <p>ONE address, not per-admin routing. There is no admin distribution list in the system,
     * and encoding whose problem each request is would duplicate the job the queue already does.
     * An address a human watches beats a routing rule nobody maintains.
     *
     * <p>Empty by default so a dev machine doesn't try to email anyone. Unset in a real
     * environment means the approval queue goes unwatched, so the handler says so loudly rather
     * than returning quietly.
     */
    @Value("${bmp.notification.ops-email:}")
    private String opsEmail;

    public NotificationDispatcher(ObjectMapper mapper, NotificationLogService logService,
                                   EmailSender emailSender, SmsSender smsSender) {
        this.mapper = mapper;
        this.logService = logService;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
    }

    @KafkaListener(topics = KafkaTopics.EVENTS, groupId = "bmp-notification-service")
    public void onEvent(@Payload String payload, @Header("eventType") String eventType) {
        try {
            switch (eventType) {
                case "otp.requested" -> handleOtpRequested(mapper.readValue(payload, OtpRequested.class));
                case "user.registered" -> handleUserRegistered(mapper.readValue(payload, UserRegistered.class));
                case "coupon_request.raised" ->
                        handleCouponRequestRaised(mapper.readValue(payload, CouponRequestRaised.class));
                case "coupon_request.decided" ->
                        handleCouponRequestDecided(mapper.readValue(payload, CouponRequestDecided.class));
                // Session 34 — see handleBookingCreated for why these were missing for so long.
                case "booking.created" -> handleBookingCreated(mapper.readValue(payload, BookingCreated.class));
                case "booking.cancelled" -> handleBookingCancelled(mapper.readValue(payload, BookingCancelled.class));
                case "booking.completed" -> handleBookingCompleted(mapper.readValue(payload, BookingCompleted.class));
                // Session 37. The one booking event that can carry news the customer hasn't heard.
                case "booking.rescheduled" ->
                        handleBookingRescheduled(mapper.readValue(payload, BookingRescheduled.class));
                default -> log.debug("ignoring event type {} — no notification consumer registered for it", eventType);
            }
        } catch (Exception e) {
            log.warn("failed to process {} event: {}", eventType, e.getMessage());
        }
    }

    private void handleOtpRequested(OtpRequested event) {
        // notification_log.recipient_user_id is NOT NULL (locked column) but an OTP send —
        // especially for a brand-new signup — happens BEFORE any user row exists. Using the
        // otp_requests row id here instead of a real user id, same "logical ref, no physical
        // FK" convention used everywhere else in this repo. templateCode=otp_code is the
        // tell for anyone reading this table that recipient_user_id isn't a real user here.
        String body = "Your BMP verification code is " + event.code() + ". It expires at " + event.expiresAt() + ".";

        dispatch(event.aggregateId(), "sms", "otp_code", Map.of("phone", event.phone()),
                () -> smsSender.send(event.phone(), body));

        if (event.email() != null) {
            dispatch(event.aggregateId(), "email", "otp_code", Map.of("email", event.email()),
                    () -> emailSender.send(event.email(), "Your BMP verification code", body));
        }
    }

    private void handleUserRegistered(UserRegistered event) {
        String body = "Welcome to Be My Professional! Your account (" + event.role() + ") is ready.";

        if (event.email() != null) {
            dispatch(event.aggregateId(), "email", "welcome", Map.of("email", event.email()),
                    () -> emailSender.send(event.email(), "Welcome to BMP", body));
        }
        dispatch(event.aggregateId(), "sms", "welcome", Map.of("phone", event.phone()),
                () -> smsSender.send(event.phone(), body));
    }

    /**
     * A coupon request is waiting for a decision — tell ops.
     *
     * <p>The summary and justification go in the BODY on purpose, so a decision can be triaged
     * from a phone without opening the console. "There is a request" is barely more useful than
     * no email; "support asked for ₹2,500 because a salon cancelled a wedding booking" is enough
     * to know whether it can wait until Monday.
     */
    private void handleCouponRequestRaised(CouponRequestRaised event) {
        if (opsEmail == null || opsEmail.isBlank()) {
            // Loud rather than silent — the entire point of this event is that somebody finds out.
            log.warn("Coupon request {} was raised but bmp.notification.ops-email is not set, so "
                    + "nobody has been told. Set it, or the approval queue goes unwatched.",
                    event.requestRef());
            return;
        }

        String subject = "[BMP] Coupon request " + event.requestRef() + " — " + event.summary();
        String body = event.summary()
                + "\n\nRaised by: " + event.requesterName() + " (" + event.requesterType() + ")"
                + "\nReason: " + event.justification()
                + "\n\nDecide it in the console, under Coupon requests.";

        dispatch(event.aggregateId(), "email", "coupon_request_raised",
                Map.of("email", opsEmail, "requestRef", event.requestRef()),
                () -> emailSender.send(opsEmail, subject, body));
    }

    /**
     * A request was decided — tell whoever asked, on whatever channels the event carries.
     *
     * <p>A staff member has an email and no phone on file; a salon owner usually has both.
     * Neither is guaranteed — a request raised while bmp-user was unreachable has neither — and
     * that case is logged rather than pretended away.
     *
     * <p>{@code grantedNote} leads the body when present. A requester who misses "you asked for
     * ₹2,000 and ₹800 was approved" quotes the original figure to a customer and has the
     * argument at their own counter.
     */
    private void handleCouponRequestDecided(CouponRequestDecided event) {
        String headline = event.approved()
                ? "Your coupon request " + event.requestRef() + " was approved."
                : "Your coupon request " + event.requestRef() + " wasn't approved.";

        StringBuilder body = new StringBuilder(headline).append("\n\n");
        if (event.grantedNote() != null) {
            body.append(event.grantedNote()).append("\n\n");
        }
        if (event.couponCode() != null) {
            body.append("The code to give your customer is: ").append(event.couponCode()).append("\n\n");
        }
        if (event.decisionNote() != null) {
            body.append(event.decisionNote()).append("\n");
        }
        String text = body.toString();

        if (event.email() != null) {
            dispatch(event.requesterUserId(), "email", "coupon_request_decided",
                    Map.of("email", event.email(), "requestRef", event.requestRef()),
                    () -> emailSender.send(event.email(), headline, text));
        }

        /*
         * SMS only for an APPROVAL that carries a code.
         *
         * A rejection needs its reasoning, and reasoning does not fit in 160 characters — a
         * truncated refusal reads worse than no message at all, because the recipient now knows
         * they were refused and not why. Email carries that one.
         */
        if (event.phone() != null && event.approved() && event.couponCode() != null) {
            dispatch(event.requesterUserId(), "sms", "coupon_request_decided",
                    Map.of("phone", event.phone(), "requestRef", event.requestRef()),
                    () -> smsSender.send(event.phone(),
                            "Your BMP offer " + event.requestRef() + " is approved. Code: " + event.couponCode()));
        }

        if (event.email() == null && event.phone() == null) {
            log.warn("Coupon request {} was decided but we hold no contact details for the "
                    + "requester — they have not been told.", event.requestRef());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════
    // Session 34 — bookings
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * A booking was made — send the customer their confirmation.
     *
     * <h2>Why this handler did not exist until Session 34</h2>
     * bmp-booking published nothing at all, so there was nothing to consume. Every piece of
     * plumbing was in place (outbox table, entity scan, relay) and the publisher was never
     * injected — while {@code BookingService.create} contained a {@code recordEvent(...)} line
     * that writes to bmp-booking's own local audit table and <em>reads</em> like publishing.
     *
     * <p>The result: a customer could book an appointment and receive nothing. The only message
     * BMP had ever sent them was their login OTP.
     *
     * <h2>The word is "requested", not "confirmed"</h2>
     * Bookings sit in {@code PENDING} until the Razorpay webhook confirms them (Phase 3,
     * unbuilt). Saying "confirmed" here would tell a customer their appointment is secured
     * before anyone has taken payment — a promise the platform has not made, and the kind that
     * gets discovered at the salon door. When {@code booking.confirmed} exists, it gets its own
     * handler and its own wording.
     *
     * <h2>Both channels, and the SMS is not a truncated email</h2>
     * They are written separately. An SMS that is an email with the middle cut out is worse than
     * a short message composed as one — and SMS costs money per send, so brevity is not only
     * aesthetic. Email carries the full detail; SMS carries what someone needs while walking.
     */
    private void handleBookingCreated(BookingCreated event) {
        String where = event.salonName() != null ? event.salonName() : "your salon";
        String when = formatWhen(event.firstStart());
        String services = event.serviceNames() == null || event.serviceNames().isEmpty()
                ? "your appointment"
                : String.join(", ", event.serviceNames());

        String subject = "Your BMP booking " + event.bookingRef() + " — " + when;
        String body = greet(event.customerName())
                + "We've sent your booking to " + where + ".\n\n"
                + "  Reference : " + event.bookingRef() + "\n"
                + "  When      : " + when + "\n"
                + "  What      : " + services + "\n"
                + "  Total     : " + rupees(event.amountPaise()) + "\n\n"
                // Honest about PENDING rather than quietly implying confirmation.
                + "The salon will confirm shortly. You can view or cancel this booking in the "
                + "BMP app under My bookings.";

        deliver(event.customerId(), "booking_created", event.email(), event.phone(), subject, body,
                "BMP: booking " + event.bookingRef() + " requested at " + where + ", " + when
                        + ". " + rupees(event.amountPaise()) + ".",
                event.bookingRef());

        notifySalonOfNewBooking(event, when, services);
    }

    /**
     * Tell the SALON someone has booked. Session 40.
     *
     * <h2>Until now the salon was never told</h2>
     * {@code booking.created} reached the customer and stopped there. The only way a salon
     * learned a customer was coming was by having the desk open, which polls every 60 seconds —
     * so a booking made overnight, or while the tablet was shut, was invisible until somebody
     * looked. For a salon that is the difference between a prepared morning and a surprise, and
     * it is about the most basic thing a booking platform owes its supply side.
     *
     * <h2>Both channels, and SMS is the point</h2>
     * A salon does not sit watching an inbox. The message that actually changes behaviour is the
     * one that arrives on the front-desk handset — which is exactly why the platform pays for it,
     * and why this is one of the few places SMS is worth the cost. The email is the record.
     *
     * <h2>The customer's phone number is NOT in this message</h2>
     * Deliberately. The salon gets a name and a time; the number is behind
     * {@code POST /bookings/{id}/reveal-contact}, which writes an audit entry the customer can
     * read. Putting it in an SMS would make every booking alert a permanent, unaudited copy of a
     * customer's number sitting in someone's phone — and would quietly undo Session 35.
     *
     * <h2>Failure is silent to the customer, loud in the log</h2>
     * The customer's confirmation has already been sent by the time this runs. A salon with no
     * alert contact set is a configuration gap, not a booking failure, and the booking is real
     * regardless.
     */
    private void notifySalonOfNewBooking(BookingCreated event, String when, String services) {
        if (event.salonNotifyEmail() == null && event.salonNotifyPhone() == null) {
            log.info("Booking {} was made but the salon has no booking-alert contact set, so "
                    + "nobody at the salon has been told. They'll see it on the desk. Set one on "
                    + "the salon record (bookingNotifyEmail / bookingNotifyPhone).",
                    event.bookingRef());
            return;
        }

        String who = event.customerName() != null ? event.customerName() : "A customer";
        String subject = "New booking — " + when + " (" + event.bookingRef() + ")";
        String body = "You have a new booking.\n\n"
                + "  Customer  : " + who + "\n"
                + "  When      : " + when + "\n"
                + "  What      : " + services + "\n"
                + "  Value     : " + rupees(event.amountPaise()) + "\n"
                + "  Reference : " + event.bookingRef() + "\n\n"
                // Honest about PENDING: the salon should not treat it as paid-for and confirmed.
                + "It's showing as requested until payment confirms. You can see it on your desk "
                + "under Today and Upcoming.\n\n"
                + "Need to reach them? Use the Call button on the booking — we'll show you the "
                + "number and record that you looked it up.";

        String sms = "BMP: new booking " + event.bookingRef() + " — " + who + ", " + when
                + ", " + services + ".";

        /*
         * recipientUserId = the SALON id, not a user id.
         *
         * notification_log.recipient_user_id is a "logical ref, no physical FK" column and the
         * same convention is already used for OTPs (which log the otp_requests row id, because
         * no user exists yet). templateCode = salon_booking_created is the tell for anyone
         * reading the table that this row's recipient is a salon.
         *
         * The alternative — resolving the owner's user id — would tie an alert to a person, and
         * the whole reason the contact lives on the salon is that the bookings inbox is a
         * business function that must survive that person leaving.
         */
        deliver(event.salonId(), "salon_booking_created",
                event.salonNotifyEmail(), event.salonNotifyPhone(),
                subject, body, sms, event.bookingRef());
    }

    /**
     * A booking was cancelled — send the receipt.
     *
     * <p>It is tempting to skip this, since the customer pressed the button themselves. The
     * message is not news, it is <b>evidence</b>: "you cancelled BMP-2026-00042 for Saturday
     * 11:00" is what a customer points at when a salon later claims they never cancelled. The
     * only other record lives in BMP's database, which is exactly the record a disputing party
     * doesn't accept.
     *
     * <p><b>No refund figure.</b> What comes back depends on the frozen {@code policy_snapshot}
     * and on a payment that doesn't exist yet. A confident refund number that turns out to be
     * wrong, in writing, is worse than no number at all — and it is the sort of wrong that
     * arrives as a chargeback. That sentence belongs to bmp-payment when there is one.
     */
    private void handleBookingCancelled(BookingCancelled event) {
        String where = event.salonName() != null ? event.salonName() : "the salon";
        String when = formatWhen(event.firstStart());

        // Today cancelledBy is always "customer" — CANCELLED is the only CUSTOMER-actor
        // transition in BookingStatus. The branch is here because when salon-initiated
        // cancellation lands, this stops being a receipt and starts being bad news, and the
        // wording has to change with it.
        boolean bySalon = "salon".equalsIgnoreCase(event.cancelledBy());

        String subject = bySalon
                ? "Your BMP booking " + event.bookingRef() + " was cancelled by the salon"
                : "Cancelled: BMP booking " + event.bookingRef();

        String body = greet(event.customerName())
                + (bySalon
                    ? where + " has cancelled this booking. We're sorry — you can rebook in the app.\n\n"
                    : "This booking has been cancelled.\n\n")
                + "  Reference : " + event.bookingRef() + "\n"
                + "  Was       : " + when + " at " + where + "\n"
                + (event.reason() != null && !event.reason().isBlank()
                    ? "  Reason    : " + event.reason() + "\n" : "")
                + "\n"
                + (bySalon
                    // Session 37. Stated plainly, because a customer whose appointment was
                    // cancelled BY the salon should not spend a second wondering what it cost
                    // them. CancellationTerms guarantees this — a salon-actor cancellation is
                    // fee-free before any policy band is consulted.
                    ? "There's nothing to pay — the salon cancelled this booking."
                    : "If a payment was taken, any refund follows the salon's cancellation policy "
                      + "as it stood when you booked. You can see the details on this booking in "
                      + "the app.");

        deliver(event.customerId(), "booking_cancelled", event.email(), event.phone(), subject, body,
                "BMP: booking " + event.bookingRef() + " (" + when + ", " + where + ") is cancelled.",
                event.bookingRef());
    }

    /**
     * The appointment happened — thank them, and state what was charged.
     *
     * <p><b>No review prompt yet, deliberately.</b> bmp-review accepts a review from anyone for
     * anything; it never checks the reviewer attended (PENDING_WORK S3). Mailing "rate your
     * visit" links before that check exists would invite exactly the fake reviews the check is
     * meant to prevent — at scale, with BMP's name on the invitation. The prompt goes in when
     * the verification does, and this event is what it will hang off.
     *
     * <p>Email only. A "thanks for visiting" SMS is the definition of a message that trains
     * people to ignore your messages, and every one costs money. The receipt is worth having in
     * writing; it isn't worth interrupting someone for.
     */
    private void handleBookingCompleted(BookingCompleted event) {
        String where = event.salonName() != null ? event.salonName() : "your salon";
        String subject = "Thanks for visiting " + where;
        String body = greet(event.customerName())
                + "Your appointment at " + where + " is complete.\n\n"
                + "  Reference : " + event.bookingRef() + "\n"
                + "  Total     : " + rupees(event.amountPaise()) + "\n\n"
                + "Thanks for booking with BMP.";

        if (event.email() == null) {
            log.info("Booking {} completed but the customer has no email on file — no receipt sent. "
                    + "This is not an error; email is optional on this platform.", event.bookingRef());
            return;
        }
        dispatch(event.customerId(), "email", "booking_completed",
                Map.of("email", event.email(), "bookingRef", event.bookingRef()),
                () -> emailSender.send(event.email(), subject, body));
    }

    /**
     * The appointment moved. Session 37.
     *
     * <h2>This is the booking event that most needs to arrive</h2>
     * The other three tell a customer something they already know — they pressed the button.
     * This one can tell them something they don't: that the SALON moved their appointment. A
     * customer who isn't told turns up at the old time, and a wasted journey caused by the
     * platform is the failure a booking app has least excuse for.
     *
     * <p>So a salon-initiated move sends over BOTH channels including SMS, where a customer's own
     * move gets email only. The bar for interrupting someone is "would they want to be
     * interrupted", and being in the wrong place at the wrong time clears it easily.
     *
     * <h2>Both times, always</h2>
     * "Your appointment is now Tuesday 3pm" is ambiguous to anyone with two bookings. "Moved from
     * Saturday 11:00 to Tuesday 15:00" is checkable against their own memory, which is the whole
     * point of telling them about a change.
     */
    private void handleBookingRescheduled(BookingRescheduled event) {
        boolean bySalon = "salon".equalsIgnoreCase(event.movedBy());
        String where = event.salonName() != null ? event.salonName() : "your salon";
        String from = formatWhen(event.previousStart());
        String to = formatWhen(event.newStart());

        String subject = bySalon
                ? where + " has moved your appointment — " + to
                : "Your BMP booking " + event.bookingRef() + " is now " + to;

        String body = greet(event.customerName())
                + (bySalon
                    ? where + " has had to move your appointment. We're sorry for the change.\n\n"
                    : "Your booking has been moved.\n\n")
                + "  Reference : " + event.bookingRef() + "\n"
                + "  Was       : " + from + "\n"
                + "  Now       : " + to + "\n"
                + (event.reason() != null && !event.reason().isBlank()
                    ? "  Reason    : " + event.reason() + "\n" : "")
                + "\n"
                + (bySalon
                    // Named explicitly, because a salon-caused move must never leave the customer
                    // feeling trapped by a fee they didn't cause.
                    ? "If the new time doesn't work, you can cancel in the app — there's nothing "
                      + "to pay when the salon has changed a booking."
                    : "You can view or change this booking in the BMP app under My bookings.");

        String sms = bySalon
                ? "BMP: " + where + " moved your booking " + event.bookingRef() + " from " + from
                  + " to " + to + ". Open the app to confirm or cancel."
                : "BMP: booking " + event.bookingRef() + " moved to " + to + ".";

        if (bySalon) {
            deliver(event.customerId(), "booking_rescheduled", event.email(), event.phone(),
                    subject, body, sms, event.bookingRef());
            return;
        }

        /*
         * The customer moved it themselves — email only.
         *
         * They are holding the phone that just did it. An SMS confirming an action taken two
         * seconds ago on the same device is the definition of the message that teaches people to
         * ignore your messages, and every send costs money. The email is still worth having, as
         * the written record of what the appointment now is.
         */
        if (event.email() == null) {
            log.info("Booking {} was rescheduled by the customer but they have no email on file — "
                    + "no confirmation sent. Not an error; email is optional on this platform.",
                    event.bookingRef());
            return;
        }
        dispatch(event.customerId(), "email", "booking_rescheduled",
                Map.of("email", event.email(), "bookingRef", event.bookingRef()),
                () -> emailSender.send(event.email(), subject, body));
    }

    // ---- shared booking helpers -------------------------------------------------------------

    /**
     * Sends the same news over both channels, and says so when it can send over neither.
     *
     * <p>The silent-failure case is the one worth caring about: a booking whose contact snapshot
     * came back empty (bmp-user was down when it was made) produces a customer who hears nothing
     * and a system that logged nothing. That is indistinguishable from working, which is the
     * worst property a notification system can have.
     */
    private void deliver(UUID customerId, String templateCode, String email, String phone,
                          String subject, String body, String sms, String bookingRef) {
        if (email == null && phone == null) {
            log.warn("Booking {} produced a {} notification but we hold NO contact details for "
                    + "customer {} — nothing has been sent. The contact snapshot (V006) was empty, "
                    + "which usually means bmp-user was unreachable when the booking was made.",
                    bookingRef, templateCode, customerId);
            return;
        }
        if (email != null) {
            dispatch(customerId, "email", templateCode,
                    Map.of("email", email, "bookingRef", bookingRef),
                    () -> emailSender.send(email, subject, body));
        }
        if (phone != null) {
            dispatch(customerId, "sms", templateCode,
                    Map.of("phone", phone, "bookingRef", bookingRef),
                    () -> smsSender.send(phone, sms));
        }
    }

    /** "Hi Priya,\n\n" — or a plain opening when we never learned their name. */
    private String greet(String name) {
        return (name == null || name.isBlank()) ? "" : "Hi " + name.trim().split("\\s+")[0] + ",\n\n";
    }

    /**
     * A time a person in Bengaluru would recognise.
     *
     * <p>{@link BmpTimeZone#ZONE}, not the server's default. The JVM's timezone is an accident of
     * deployment, and a booking rendered in UTC reads as five and a half hours earlier — which
     * on an evening appointment is the wrong DAY. This is the single most consequential
     * formatting call in the codebase.
     */
    private String formatWhen(java.time.Instant instant) {
        if (instant == null) {
            return "your booked time";
        }
        return java.time.format.DateTimeFormatter
                .ofPattern("EEE d MMM, h:mm a", java.util.Locale.ENGLISH)
                .format(instant.atZone(BmpTimeZone.ZONE));
    }

    /**
     * Paise to rupees for display. Integer arithmetic only — money is never a float in this
     * codebase, and that rule does not get relaxed just because the result is being printed.
     */
    private String rupees(long paise) {
        return String.format(java.util.Locale.ENGLISH, "₹%d.%02d", paise / 100, Math.abs(paise % 100));
    }

    private void dispatch(UUID recipientUserId, String channel, String templateCode,
                           Map<String, Object> payload, Runnable send) {
        var logged = logService.log(new LogRequest(recipientUserId, channel, templateCode, payload));
        try {
            send.run();
            logService.markSent(logged.id());
        } catch (Exception e) {
            logService.markFailed(logged.id(), e.getMessage());
            log.warn("notification send failed [{} / {}]: {}", channel, templateCode, e.getMessage());
        }
    }
}
