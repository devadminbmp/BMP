package com.bmp.notification.services;

import com.bmp.common.events.BookingCancelled;
import com.bmp.common.events.BookingCompleted;
import com.bmp.common.events.BookingCreated;
import com.bmp.common.events.BookingRescheduled;
import com.bmp.common.events.SalonStatusChanged;
import com.bmp.common.events.BookingAffectedByClosure;
import com.bmp.common.events.SupportTicketReplied;
import com.bmp.common.events.CouponRequestDecided;
import com.bmp.common.events.CouponRequestRaised;
import com.bmp.common.events.OtpRequested;
import com.bmp.common.events.StylistJoinRequestDecided;
import com.bmp.common.events.StylistLeaveDecided;
import com.bmp.common.events.StylistRemovedFromSalon;
import com.bmp.common.events.StylistSuspensionChanged;
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

    /**
     * Stand-in recipient for a notification whose subject has no user account. Session 47.
     *
     * <p>{@code notification_log.recipient_user_id} is NOT NULL, but plenty of real messages go to
     * someone we can't name: a salon whose owner lookup failed, a phone-in with no account. The
     * all-zero UUID is an obvious sentinel — nobody mistakes it for a real id — and the row's
     * {@code template_code} plus {@code payload} still say who it was actually for.
     */
    private static final UUID UNKNOWN_RECIPIENT = new UUID(0L, 0L);

    private final ObjectMapper mapper;
    private final NotificationLogService logService;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    /** Session 43 — its own channel, not a flavour of SMS. See {@link WhatsAppSender}. */
    private final WhatsAppSender whatsAppSender;

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
                                   EmailSender emailSender, SmsSender smsSender,
                                   WhatsAppSender whatsAppSender) {
        this.mapper = mapper;
        this.logService = logService;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.whatsAppSender = whatsAppSender;
    }

    @KafkaListener(topics = KafkaTopics.EVENTS, groupId = "bmp-notification-service")
    public void onEvent(@Payload String payload, @Header("eventType") String eventType) {
        try {
            switch (eventType) {
                case "otp.requested" -> handleOtpRequested(mapper.readValue(payload, OtpRequested.class));
                // Session 65 — a SEPARATE event from otp.requested on purpose. See the handler.
                case "contact_change.code_requested" -> handleContactChangeCode(
                        mapper.readValue(payload, com.bmp.common.events.ContactChangeCodeRequested.class));
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
                // Session 46 — the moment a partner has been waiting for, and until now the one
                // BMP said nothing about. See the handler.
                case "booking.affected_by_closure" ->
                        handleAffectedByClosure(mapper.readValue(payload, BookingAffectedByClosure.class));
                case "support_ticket.replied" ->
                        handleSupportReplied(mapper.readValue(payload, SupportTicketReplied.class));
                case "salon.status.changed" ->
                        handleSalonStatusChanged(mapper.readValue(payload, SalonStatusChanged.class));
                // Session 49 — two flows built in Session 48/49 that told the stylist nothing.
                // See each handler for the silence it ends.
                case "stylist.join_request.decided" ->
                        handleStylistJoinDecided(mapper.readValue(payload, StylistJoinRequestDecided.class));
                // Session 65 (V028) — the invite code, delivered instead of read out over the phone.
                case "salon.staff_invite.issued" ->
                        handleStaffInviteIssued(mapper.readValue(
                                payload, com.bmp.common.events.StaffInviteIssued.class));
                case "stylist.leave.decided" ->
                        handleStylistLeaveDecided(mapper.readValue(payload, StylistLeaveDecided.class));
                // Session 51 — the most serious message BMP sends anybody. See the handler.
                case "stylist.suspension.changed" ->
                        handleSuspensionChanged(mapper.readValue(payload, StylistSuspensionChanged.class));
                case "stylist.removed_from_salon" ->
                        handleRemovedFromSalon(mapper.readValue(payload, StylistRemovedFromSalon.class));
                // Session 53 — the person who has to be at the chair was the only party never told.
                case "stylist.appointment.changed" ->
                        handleStylistAppointment(mapper.readValue(
                                payload, com.bmp.common.events.StylistAppointmentChanged.class));
                default -> log.debug("ignoring event type {} — no notification consumer registered for it", eventType);
            }
        } catch (Exception e) {
            // Session 47: the throwable, not its message — see dispatch() for why that mattered.
            // A JSON shape mismatch between an event record and its consumer shows up here, and
            // the message alone ("Cannot construct instance of ...") omits which field.
            log.error("Failed to process {} event: {}", eventType, rootCauseOf(e), e);
        }
    }

    /**
     * Confirm a self-service phone or email change. Session 65.
     *
     * <h2>This email names the change, and that is the whole point</h2>
     * The login-code email says "enter this in the app to continue". Sent for a contact change it
     * would be actively misleading in the case that matters most: somebody who did NOT request
     * this needs to read that their address or number is being changed, not that a login is being
     * attempted. For an email change this message goes to the NEW address, so it is also the only
     * thing standing between a mistyped address and a confirmed one.
     *
     * <p>The old address is NOT notified here. That is a gap, not a decision I can defend — a
     * "your contact details were changed" notice to the previous address is the standard warning
     * and is worth adding. It needs the old value threaded through the event, which this one does
     * not carry.
     */
    private void handleContactChangeCode(com.bmp.common.events.ContactChangeCodeRequested event) {
        String expiry = expiryPhrase(event.expiresAt());
        boolean emailChange = event.newEmail() != null;

        String what = emailChange
                ? "email address to " + event.newEmail()
                : "phone number to " + event.newPhone();

        String subject = emailChange
                ? "Confirm your new BMP email address"
                : "Confirm your new BMP phone number";

        String textBody = """
                You asked to change your BMP %s

                Your confirmation code is %s

                It expires %s.

                If this wasn't you, do not enter this code — and contact support straight away,
                because somebody else may have access to your account.

                — BMP
                """.formatted(what, event.code(), expiry);

        String htmlBody = EmailTemplate.card(
                "Confirm your new " + (emailChange ? "email address" : "phone number") + ".",
                emailChange ? "Confirm your new email address" : "Confirm your new phone number",
                "You asked to change your BMP " + what + ". Enter this code in the app to confirm. "
                        + capitalise(expiry) + ".",
                java.util.List.of(),
                event.code(),
                "If this wasn't you, do not enter this code — contact support straight away, "
                        + "because somebody else may have access to your account.",
                EmailTemplate.Tone.INFO);

        dispatch(event.aggregateId(), "email", "contact_change_code",
                Map.of("email", event.sentToEmail()),
                () -> emailSender.sendHtml(event.sentToEmail(), subject, htmlBody, textBody));
    }

    private void handleOtpRequested(OtpRequested event) {
        // notification_log.recipient_user_id is NOT NULL (locked column) but an OTP send —
        // especially for a brand-new signup — happens BEFORE any user row exists. Using the
        // otp_requests row id here instead of a real user id, same "logical ref, no physical
        // FK" convention used everywhere else in this repo. templateCode=otp_code is the
        // tell for anyone reading this table that recipient_user_id isn't a real user here.
        /*
         * Session 47 — this used to interpolate the raw Instant, so people received:
         *
         *     "It expires at 2026-08-27T17:39:27.621829600Z."
         *
         * Machine format, nanoseconds, and UTC — telling an Indian reader a time five and a half
         * hours before the real one. Session 47 replaced it with "in about 5 minutes (11:13 pm)".
         *
         * SESSION 48 DROPPED THE CLOCK TIME TOO. The bracket was there for precision and nobody
         * wanted it: a person holding a code needs to know how long they have, and a wall-clock
         * time makes them work that out by subtraction. Two ways of saying the same thing is not
         * twice as clear, it is a second thing to read. "It expires in the next 5 minutes." is
         * the whole message.
         *
         * expiryPhrase() still rounds UP, so we never tell anyone they have longer than they do.
         */
        String expiry = expiryPhrase(event.expiresAt());

        /*
         * ── WHICH NUMBER IS THIS CODE FOR? Session 65. ───────────────────────────────────────
         *
         * The email is NOT unique on BMP and is not meant to be — the phone is the identity, and
         * one inbox legitimately serves several accounts (a family with one email and separate
         * numbers; a founder with test accounts). Session 65's identity-doctor found exactly that:
         * two different numbers both carrying darshandn0206@gmail.com.
         *
         * Which meant two accounts' codes arriving in ONE inbox, in identical emails, with nothing
         * distinguishing them. The only way to tell which code belonged to which login attempt was
         * the timestamp — and if you request one, get distracted, and request the other, even that
         * fails. It reads as "the wrong code is being sent", which is how it was reported.
         *
         * So the number goes in. LAST FOUR DIGITS in the subject, because subject lines appear in
         * lock-screen previews and a full mobile number sitting on a locked phone is a gift to
         * whoever picks it up. The full number goes in the body, where opening the mail is already
         * a deliberate act.
         */
        String subject = "Your BMP verification code (" + lastFour(event.phone()) + ")";

        String textBody = """
                Your BMP verification code is %s

                This code signs you in as %s. It expires %s.

                Didn't ask for this? You can ignore this email — the code only works on the device
                that requested it, and it stops working on its own.

                — BMP
                """.formatted(event.code(), event.phone(), expiry);

        /*
         * The code goes in the CALLOUT, on its own, spaced out.
         *
         * Most people do not retype an OTP, they select it — and a six-digit number buried inside
         * a sentence is hard to select cleanly on a phone. In its own block it can be
         * double-tapped, and iOS/Android autofill are far more likely to detect it. The subject
         * line carries the code's purpose, not the code: OTPs show up in lock-screen previews.
         */
        String htmlBody = EmailTemplate.card(
                "Your verification code — it expires " + expiry + ".",
                "Here's your code",
                // The number is named in the INTRO, above the code, so somebody with several BMP
                // accounts in one inbox can tell at a glance which login this belongs to without
                // opening two emails and comparing timestamps.
                "Signing in as " + event.phone() + ". " + capitalise(expiry) + ".",
                java.util.List.of(),
                event.code(),
                "Didn't ask for this? You can safely ignore this email. The code only works on the "
                        + "device that asked for it, and it stops working on its own.",
                EmailTemplate.Tone.INFO);

        // Kept for the SMS/WhatsApp stubs below, which are plain-text channels by nature.
        String body = textBody;

        // ── EMAIL IS THE LIVE CHANNEL ────────────────────────────────────────────────────────
        // Session 43: deliberately dispatched FIRST, ahead of the two stubs. Ordering has no
        // technical effect here, but it puts the channel that actually reaches a human at the
        // top of the method, where the next reader looks. The two below are placeholders.
        //
        // If this is missing, nobody can log in — there is no other working route to the code.
        if (event.email() != null) {
            dispatch(event.aggregateId(), "email", "otp_code", Map.of("email", event.email()),
                    () -> emailSender.sendHtml(event.email(), subject, htmlBody, textBody));
        } else {
            // Loud, because this is a dead end for the user: no email means no code, and the
            // stubs below will not save them. AuthService requires an email for signup and
            // reuses the stored one for login, so reaching here means something upstream broke.
            log.error("OTP for phone={} has NO email address — the code cannot be delivered. "
                    + "SMS and WhatsApp are stubs (configuration pending); email is the only "
                    + "live channel.", event.phone());
        }

        // ── CONFIGURATION PENDING: nothing below actually sends ──────────────────────────────
        // Both are wired at the correct call site and disabled by config, so switching either on
        // is one bean plus one flag rather than an archaeology exercise to find every send site.
        // See LoggingSmsSender (blocked on DLT registration) and LoggingWhatsAppSender (blocked
        // on a WhatsApp Business account + template approval).
        dispatch(event.aggregateId(), "sms", "otp_code", Map.of("phone", event.phone()),
                () -> smsSender.send(event.phone(), body));

        // Template name, not free text: WhatsApp will only deliver a Meta-approved template to a
        // user outside the 24-hour window, and an OTP is by definition unsolicited. Passing it
        // now keeps the call site correct for the real implementation.
        dispatch(event.aggregateId(), "whatsapp", "otp_code", Map.of("phone", event.phone()),
                () -> whatsAppSender.send(event.phone(), "otp_code", body));
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
    // Session 46 — salon approval
    // ═════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Tell a salon owner they were approved, rejected or suspended.
     *
     * <h2>Why this was the most conspicuous silence in the product</h2>
     * {@code SalonModerationService.decide} published nothing at all. A moderator approved a
     * salon and the owner found out by opening the app and guessing; a rejection they found out
     * never. It is the single most anticipated moment in a partner's relationship with BMP — the
     * thing they signed up for and are actively waiting on — and sending nothing turns a decision
     * into a silence indistinguishable from being ignored.
     *
     * <h2>The rejection note is the message</h2>
     * On a rejection the moderator's note is not a footnote, it is the entire useful content:
     * without it the owner knows only that they failed and not what to change. bmp-admin already
     * refuses a rejection with a note under ten characters for that reason. So the note is
     * rendered prominently, and its absence is logged loudly rather than producing a bland
     * "unfortunately" email that guarantees a support ticket.
     */
    private void handleSalonStatusChanged(SalonStatusChanged event) {
        String subject;
        /*
         * The inbox preview line. Worth writing separately rather than letting the client grab the
         * first sentence of the body: this is the text sitting next to the subject when the owner
         * decides whether to open the mail, and "Your salon ID is inside" earns an open in a way
         * that a repeat of the heading does not.
         */
        String preheader;
        String heading;
        String intro;
        String callout = null;
        String outro;
        EmailTemplate.Tone tone;
        java.util.List<EmailTemplate.Row> rows = java.util.List.of();
        StringBuilder body = new StringBuilder();   // the plain-text alternative

        String salon = event.salonName();

        switch (event.status()) {
            case "approved" -> {
                /*
                 * THE WELCOME EMAIL. Session 48.
                 *
                 * This is the moment the owner has been waiting for since they submitted, and
                 * until now it was two flat sentences. It is also the only email in the product
                 * that gets kept: the salon ID lives here, and it is what support will ask for
                 * on every call afterwards. So it carries the identifiers in a panel that
                 * survives forwarding, rather than in a sentence people have to hunt through.
                 */
                subject = salon + " is live on BMP 🎉";
                preheader = "You're approved. Your salon ID and support details are inside.";
                heading = "Welcome aboard, " + salon + "!";
                intro = "Your salon has been approved and is now visible to customers across "
                        + "Bengaluru. From this moment they can find you and book you.";
                tone = EmailTemplate.Tone.GOOD;
                rows = java.util.List.of(
                        EmailTemplate.Row.of("Salon", salon),
                        // Monospace: this is the number people read down a phone line to support.
                        EmailTemplate.Row.id("Salon ID", salonRef(event)),
                        EmailTemplate.Row.of("Status", "Approved — live to customers"),
                        EmailTemplate.Row.of("Support", EmailTemplate.SUPPORT_EMAIL),
                        EmailTemplate.Row.of("Phone", EmailTemplate.SUPPORT_PHONE));
                callout = "Two things worth doing today: check your map pin is exactly right — it "
                        + "decides who finds you — and add photos, because salons with photos get "
                        + "chosen more often.";
                outro = "Keep this email. Your salon ID above is what our team will ask for if you "
                        + "ever need help with your listing.";

                body.append("Welcome aboard, ").append(salon).append("!\n\n")
                    .append("Your salon has been approved and is now visible to customers on BMP.\n\n")
                    .append("Salon:    ").append(salon).append("\n")
                    .append("Salon ID: ").append(salonRef(event)).append("\n")
                    .append("Support:  ").append(EmailTemplate.SUPPORT_EMAIL)
                    .append("  ").append(EmailTemplate.SUPPORT_PHONE).append("\n\n")
                    .append("Two things worth doing today:\n")
                    .append("  - check your map pin is exactly right — it decides who finds you\n")
                    .append("  - add photos, because salons with photos get chosen more often\n\n")
                    .append("Keep this email; your salon ID is what support will ask for.\n");
            }
            case "rejected" -> {
                subject = "About your BMP application for " + salon;
                preheader = "Here's exactly what to change before you submit again.";
                heading = "We can't approve " + salon + " yet";
                intro = "We've reviewed your application. It isn't a no forever — here's exactly "
                        + "what needs to change.";
                tone = EmailTemplate.Tone.ATTENTION;

                body.append("We've reviewed ").append(salon).append(" and can't approve it yet.\n\n");
                if (event.decisionNote() != null && !event.decisionNote().isBlank()) {
                    callout = event.decisionNote();
                    body.append("What needs to change:\n").append(event.decisionNote()).append("\n\n");
                } else {
                    // Should be unreachable — bmp-admin enforces a note on rejection. If it ever
                    // happens, the owner has been told "no" with no way to act, so say so here
                    // rather than sending a dead end.
                    log.error("Salon {} was REJECTED with no decision note. The owner cannot know "
                            + "what to fix — this should be impossible; check the moderation flow.",
                            event.aggregateId());
                    callout = "Please contact us and we'll explain exactly what's needed.";
                    body.append("Please contact us and we'll explain what's needed.\n\n");
                }
                if (event.canResubmit()) {
                    outro = "Fix the above and submit again from the app — open BMP and you'll see "
                          + "what to do. Resubmitting puts you straight back in the queue.";
                    body.append("You can fix this and submit again from the app — open BMP and "
                              + "you'll see what to do.\n");
                } else {
                    outro = "If any of this doesn't look right, reply to this email and a person "
                          + "will look at it.";
                }
            }
            case "suspended" -> {
                subject = salon + " has been suspended on BMP";
                preheader = "Your listing is hidden from customers while we look into this.";
                heading = salon + " has been suspended";
                intro = "Your salon is no longer visible to customers while we look into this.";
                tone = EmailTemplate.Tone.ATTENTION;
                body.append(salon).append(" has been suspended and is no longer visible to customers.\n\n");
                if (event.decisionNote() != null && !event.decisionNote().isBlank()) {
                    callout = event.decisionNote();
                    body.append(event.decisionNote()).append("\n\n");
                }
                // Deliberately no "resubmit" line: suspension is a decision made about a salon
                // that was already trading, and it is reversed by a person, not by a form.
                outro = "Please get in touch through the Help tab so we can talk it through.";
                body.append("Please get in touch through the Help tab so we can talk it through.\n");
            }
            case "deleted" -> {
                /*
                 * Session 48. Without this case the status fell through to `default`, which logs
                 * and returns — so an admin could take a salon off the site and the owner would
                 * learn about it by opening the app to an empty dashboard. Removing somebody's
                 * business from a platform without telling them is not something to do by
                 * omission.
                 *
                 * ATTENTION, not GOOD or INFO: this is the most consequential thing that can
                 * happen to a partner, and the note is the whole message.
                 */
                subject = salon + " has been removed from BMP";
                preheader = "Your listing is no longer on BMP. Here's why, and what to do.";
                heading = salon + " has been removed from BMP";
                intro = "Your salon is no longer listed. Customers cannot find or book you.";
                tone = EmailTemplate.Tone.ATTENTION;
                if (event.decisionNote() != null && !event.decisionNote().isBlank()) {
                    callout = event.decisionNote();
                } else {
                    // bmp-admin requires a note on this action, so reaching here means something
                    // upstream skipped the validation. Loud, because the owner has been removed
                    // with no explanation at all.
                    log.error("Salon {} was DELETED with no note. The owner cannot know why — "
                            + "this should be impossible; check the console flow.", event.aggregateId());
                    callout = "Please contact us and we'll explain.";
                }
                outro = "If you think this is a mistake, reply to this email or use the Help tab — "
                      + "a person will look at it. Your booking history and records are kept.";

                body.append(salon).append(" has been removed from BMP and is no longer listed.\n\n");
                if (event.decisionNote() != null && !event.decisionNote().isBlank()) {
                    body.append(event.decisionNote()).append("\n\n");
                }
                body.append("If you think this is a mistake, reply to this email or use the Help "
                          + "tab. Your booking history and records are kept.\n");
            }
            case "pending" -> {
                /*
                 * THE RECEIPT. Session 48 — and a reversal of Session 45's decision.
                 *
                 * This case used to fall through to `default` with the reasoning that the owner
                 * had just pressed the button themselves, so confirming it was noise. That was
                 * wrong, and wrong in a way that only shows up once real people use the product:
                 * after submitting, the owner hears nothing until a moderator happens to look —
                 * possibly the next working day. Silence after a form is not read as "received",
                 * it is read as "that didn't work", and the rational response is to fill the form
                 * in again. Duplicate salons in the review queue are the visible symptom.
                 *
                 * Sent for a resubmission too. Someone who was rejected, fixed things and
                 * resubmitted is MORE anxious about whether it landed, not less.
                 */
                boolean again = event.canResubmit();   // false on first submission; see SalonService
                subject = "We've received your BMP application for " + salon;
                preheader = "It's with our team now. We'll email you as soon as it's reviewed.";
                heading = "Thanks — we've got it";
                intro = "Your application for " + salon + " has reached our team and is in the "
                      + "queue for review. You don't need to do anything else right now.";
                tone = EmailTemplate.Tone.INFO;
                rows = java.util.List.of(
                        EmailTemplate.Row.of("Salon", salon),
                        EmailTemplate.Row.id("Reference", salonRef(event)),
                        EmailTemplate.Row.of("Status", "Pending review"));
                callout = "Most applications are reviewed within one to two working days. We'll "
                        + "email you the moment there's a decision — approved or not.";
                outro = "You can sign in to BMP any time to check your status. If anything looks "
                      + "wrong on your listing, tell us now and we'll sort it before review.";

                body.append("Thanks — we've got it.\n\n")
                    .append("Your application for ").append(salon)
                    .append(" has reached our team and is in the queue for review.\n\n")
                    .append("Salon:     ").append(salon).append("\n")
                    .append("Reference: ").append(salonRef(event)).append("\n")
                    .append("Status:    Pending review\n\n")
                    .append("Most applications are reviewed within one to two working days. "
                          + "We'll email you the moment there's a decision.\n\n")
                    .append("Questions? ").append(EmailTemplate.SUPPORT_EMAIL).append("\n");

                // Silences the "unused" reading of `again` while keeping the distinction visible
                // for whoever wants to word the two cases differently later.
                log.debug("Submission receipt for salon {} (resubmission={})", event.aggregateId(), again);

                /*
                 * AND TELL OUR OWN TEAM. Session 48.
                 *
                 * The salon is put in the moderation queue by SalonService, but nothing pushed
                 * that fact at anybody — it sat there until a human happened to open the console
                 * and look. A queue nobody is told about is a queue that goes stale, and the
                 * person paying for that is the owner sitting on "pending review" wondering if
                 * they did something wrong.
                 *
                 * Sent to the same ops address as coupon requests, so there is one inbox to watch
                 * rather than a new one to remember.
                 */
                notifyOpsOfSalonSubmission(event, again);
            }
            default -> {
                log.debug("salon.status.changed to {} for salon {} — no notification for this state",
                        event.status(), event.aggregateId());
                return;
            }
        }

        String text = body.toString();

        /*
         * Effectively-final copies for the lambda below.
         *
         * `rows` and `callout` are assigned inside the switch, so javac will not let the lambda
         * capture them directly. Copying is the boring fix and the right one — the alternative
         * (a mutable holder, or building the HTML eagerly above) would either obscure the flow or
         * do the work even when there is no email address to send it to.
         */
        final java.util.List<EmailTemplate.Row> rowsFinal = rows;
        final String calloutFinal = callout;

        // ── EMAIL IS THE LIVE CHANNEL ───────────────────────────────────────────────────────
        if (event.ownerEmail() != null) {
            dispatch(event.ownerUserId(), "email", "salon_status_" + event.status(),
                    Map.of("email", event.ownerEmail(), "salon", event.salonName()),
                    () -> emailSender.sendHtml(event.ownerEmail(), subject,
                            EmailTemplate.card(preheader, heading, intro, rowsFinal, calloutFinal,
                                    outro, tone),
                            text));
        }

        /*
         * SMS carries the APPROVAL only — see handleCouponRequestDecided for the same reasoning.
         * A rejection needs its explanation, and an explanation does not survive 160 characters:
         * a truncated refusal is worse than no message, because the owner now knows they were
         * refused and still not why. Email carries that one.
         */
        if (event.ownerPhone() != null && "approved".equals(event.status())) {
            dispatch(event.ownerUserId(), "sms", "salon_status_approved",
                    Map.of("phone", event.ownerPhone(), "salon", event.salonName()),
                    () -> smsSender.send(event.ownerPhone(),
                            event.salonName() + " is now live on BMP. Customers can find and book you."));
        }

        if (event.ownerEmail() == null && event.ownerPhone() == null) {
            log.warn("Salon {} was {} but we hold no contact details for its owner — they have "
                    + "NOT been told. They will discover this by opening the app, if they do.",
                    event.aggregateId(), event.status());
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
    /**
     * "A salon is waiting for review" — to our own team, not the owner. Session 48.
     *
     * <p>Plain text, not the branded card: this is internal mail whose only job is to get someone
     * into the console. A designed template would be effort spent on an audience of three.
     *
     * <p>Silent when no ops address is configured — same rule as the coupon handler. A missing
     * internal recipient must never turn into an error on a signup that otherwise worked.
     */
    /**
     * A support agent answered — tell the person who asked. Session 48.
     *
     * <h2>Why the agent's words go through verbatim</h2>
     * No summarising, no template around the message beyond a greeting. The agent wrote a reply to
     * a specific person about a specific problem; anything this method adds is noise between them,
     * and anything it removes is the answer.
     *
     * <h2>Named human, not "BMP Support"</h2>
     * The agent's name is on it. A reply signed by a person reads as somebody having looked at
     * your problem; the same words from a faceless queue read as an autoresponder, which is what
     * people ignore.
     *
     * <p>The publisher has already excluded internal notes — see SupportDeskController. This
     * handler does NOT re-check, deliberately: two places deciding the same thing is how they end
     * up disagreeing, and the publisher is the one that can see the flag.
     */
    /**
     * "The salon is closing that day" — sent BEFORE anybody has decided what happens. Session 48.
     *
     * <h2>The wording is the whole design</h2>
     * This must not say the booking is cancelled, because it usually is not — most end up moved.
     * Telling somebody their appointment is gone and then moving it is worse than telling them
     * nothing: they have already made other plans. So it says the salon will be shut, that the
     * salon will be in touch, and nothing about the outcome.
     *
     * <p>ATTENTION rather than INFO: the customer has an appointment that will not happen as
     * planned, and burying that in a neutral tone is how it gets skimmed past.
     */
    private void handleAffectedByClosure(BookingAffectedByClosure event) {
        if (event.customerEmail() == null || event.customerEmail().isBlank()) {
            log.warn("Booking {} is inside a closure but carries no customer email — they have "
                    + "NOT been warned.", event.bookingRef());
            return;
        }

        String greeting = event.customerName() == null || event.customerName().isBlank()
                ? "Hello," : "Hi " + event.customerName() + ",";
        String when = formatWhen(event.serviceStart());
        String salon = event.salonName() == null ? "the salon" : event.salonName();

        String subject = salon + " is closed on " + when + " — about your booking";

        String callout = event.reason() != null && !event.reason().isBlank()
                ? event.reason()
                : null;

        String textBody = greeting + "\n\n"
                + salon + " has told us they will be closed at the time of your appointment on "
                + when + ".\n\n"
                + (callout != null ? salon + " says: " + callout + "\n\n" : "")
                + "Your booking has NOT been cancelled. The salon will contact you to move it to "
                + "another time, or to refund you if that suits you better.\n\n"
                + "Booking: " + event.bookingRef() + "\n\n"
                + "We're telling you now so you don't travel there for nothing.\n";

        String htmlBody = EmailTemplate.card(
                salon + " will be closed at the time of your appointment.",
                salon + " is closed then",
                greeting + " " + salon + " has told us they'll be closed at the time of your "
                        + "appointment. We wanted you to know before you travelled.",
                java.util.List.of(
                        EmailTemplate.Row.of("Your appointment", when),
                        EmailTemplate.Row.id("Booking", event.bookingRef()),
                        EmailTemplate.Row.of("Salon", salon)),
                callout,
                "Your booking has NOT been cancelled. The salon will be in touch to move it, or to "
                        + "refund you if that suits you better.",
                EmailTemplate.Tone.ATTENTION);

        dispatch(event.aggregateId(), "email", "booking_affected_by_closure",
                Map.of("email", event.customerEmail(), "bookingRef", event.bookingRef()),
                () -> emailSender.sendHtml(event.customerEmail(), subject, htmlBody, textBody));
    }

    private void handleSupportReplied(SupportTicketReplied event) {
        if (event.requesterEmail() == null || event.requesterEmail().isBlank()) {
            // Should be unreachable — the publisher checks — so this is a real fault if it fires.
            log.error("Support reply for ticket {} arrived with NO requester email. The customer "
                    + "has not been told and does not know their ticket was answered.",
                    event.ticketRef());
            return;
        }

        String greeting = event.requesterName() == null || event.requesterName().isBlank()
                ? "Hello,"
                : "Hi " + event.requesterName() + ",";
        String from = event.agentName() == null || event.agentName().isBlank()
                ? "the BMP team"
                : event.agentName();

        String subject = "Re: " + (event.subject() == null || event.subject().isBlank()
                ? "your BMP support request"
                : event.subject())
                + " (" + event.ticketRef() + ")";

        String textBody = greeting + "\n\n"
                + event.messageText() + "\n\n"
                + "— " + from + ", BMP Support\n\n"
                + "Ticket: " + event.ticketRef() + "\n"
                + "Just reply to this email if you need anything else.\n";

        String htmlBody = EmailTemplate.card(
                "A reply from " + from + " about " + event.ticketRef() + ".",
                "We've replied to your request",
                greeting + " " + from + " has answered your support request.",
                java.util.List.of(
                        EmailTemplate.Row.id("Ticket", event.ticketRef()),
                        EmailTemplate.Row.of("About", event.subject() == null ? "—" : event.subject())),
                // The reply itself, in the callout — it is the one thing on this page that matters.
                event.messageText(),
                "Reply to this email if you need anything else and it will reach the same team.",
                EmailTemplate.Tone.INFO);

        dispatch(event.aggregateId(), "email", "support_reply",
                Map.of("email", event.requesterEmail(), "ticketRef", event.ticketRef()),
                () -> emailSender.sendHtml(event.requesterEmail(), subject, htmlBody, textBody));
    }

    /**
     * A salon answered a stylist's request to join. Session 49.
     *
     * <h2>The silence this ends</h2>
     * Session 48 built the whole self-signup flow and told the stylist nothing about the outcome.
     * The decision landed in a list they had to remember to open — and somebody who has asked and
     * heard nothing cannot tell "not looked at yet" from "declined" from "the app is broken".
     * All three lead to asking again.
     *
     * <h2>The decline carries the reason, and that is the point</h2>
     * A decline REQUIRES a note server-side. "Your request was declined" without it produces the
     * same request next week; the owner's actual words ("we don't have anyone by that name here")
     * let the stylist fix a real mistake — wrong branch, or a name the owner didn't recognise.
     */
    /**
     * Email a one-time invite code to somebody who does not have a BMP account yet. Session 65.
     *
     * <h2>The failure this replaces</h2>
     * Darshan: "we need take stylist email id also hence we can send him invitation and code
     * beautifully in email but u r taking only name and number".
     *
     * <p>The code was previously visible only on the owner's screen, so it travelled by being read
     * aloud or retyped into a chat. A token is 32 URL-safe characters, and a single mistyped one
     * produces {@code INVITE_NOT_FOUND_OR_ALREADY_USED} — an error that says "this code is used
     * up", which is the one thing that is definitely not true. The invitee blames the app, the
     * owner blames the invitee, and nobody suspects the fourth character.
     *
     * <h2>THE RECIPIENT HAS NO ACCOUNT — that is the entire point of this email</h2>
     * There is no user id to log against, no in-app inbox to fall back on, and no second channel.
     * If this email does not arrive, the invitee's only route in is the owner reciting the code.
     * So {@code recipientUserId} is the INVITE id, following the same "logical ref, no physical
     * FK" convention handleOtpRequested uses for exactly the same reason.
     *
     * <h2>Three things the body must say, in this order</h2>
     * <ol>
     *   <li><b>Who invited them</b> — an email containing only a code is indistinguishable from
     *       phishing, and the correct response to that email is to delete it.</li>
     *   <li><b>The code</b>, in monospace. It gets retyped by hand on a phone keyboard, so 0/O and
     *       1/l must not be guessable.</li>
     *   <li><b>The phone number it is locked to.</b> The commonest way this flow fails is signing
     *       up with a different number — a work phone, or the same number typed without +91 — and
     *       getting {@code INVITE_PHONE_MISMATCH}, which explains nothing about which number was
     *       expected. Stating it up front costs one row and removes the whole failure class.</li>
     * </ol>
     */
    private void handleStaffInviteIssued(com.bmp.common.events.StaffInviteIssued event) {
        if (event.inviteeEmail() == null || event.inviteeEmail().isBlank()) {
            /*
             * Should not happen — bmp-salon only publishes this when an address was supplied — so
             * it is a WARN about a publisher bug rather than an expected state, and it returns
             * rather than throwing: there is nothing a retry would fix.
             */
            log.warn("staff_invite.issued for invite {} carried no email address. Nothing sent; the "
                    + "owner is still holding the code.", event.aggregateId());
            return;
        }

        boolean stylist = "stylist".equalsIgnoreCase(event.role());
        String greeting = event.inviteeName() == null || event.inviteeName().isBlank()
                ? "Hello," : "Hi " + event.inviteeName() + ",";

        String subject = event.salonName() + " has invited you to join them on BMP";

        /*
         * What redeeming ACTUALLY creates differs by role, and the invitee deserves to know which
         * they are agreeing to before they sign up:
         *   · stylist → a portable profile. Their rating and reviews are theirs and travel with
         *     them if they ever change salons. They do not get dashboard access.
         *   · manager → a seat at this salon's desk. Access to bookings, customers and the floor.
         */
        String whatThisIs = stylist
                ? "You'll have your own stylist profile — your rating and reviews belong to you, "
                  + "not the salon, and they travel with you if you ever move."
                : "You'll be a manager at " + event.salonName() + ", with access to their bookings "
                  + "and daily schedule.";

        String expiry = event.expiresAt() == null ? "soon"
                : "on " + java.time.format.DateTimeFormatter
                        .ofPattern("d MMM 'at' h:mma")
                        .withZone(java.time.ZoneId.of("Asia/Kolkata"))
                        .format(event.expiresAt());

        String textBody = greeting + "\n\n"
                + event.salonName() + " has invited you to join them on BMP as a "
                + (stylist ? "stylist" : "manager") + ".\n\n"
                + "Your invite code: " + event.token() + "\n\n"
                + "To use it: download BMP, sign up with " + event.phone()
                + " (it must be this number), and enter the code when you're asked for one.\n\n"
                + whatThisIs + "\n\n"
                + "The code can only be used once and expires " + expiry + ".\n\n"
                + "If you weren't expecting this, you can ignore it — nothing happens unless you "
                + "use the code.\n";

        String htmlBody = EmailTemplate.card(
                event.salonName() + " invited you to join their team on BMP.",
                "You've been invited",
                greeting + " " + event.salonName() + " has invited you to join them on BMP as a "
                        + (stylist ? "stylist" : "manager") + ". Here's your code.",
                java.util.List.of(
                        // .id() renders monospace — this one is going to be retyped by hand.
                        EmailTemplate.Row.id("Your invite code", event.token()),
                        // The number is a ROW rather than prose because it is a value to check,
                        // not a sentence to read, and it is the field this flow fails on.
                        EmailTemplate.Row.of("Sign up with this number", event.phone()),
                        EmailTemplate.Row.of("Invited by", event.salonName()),
                        EmailTemplate.Row.of("Code expires", expiry)),
                "Sign up with " + event.phone() + " — the code is locked to that number and won't "
                        + "work with any other.",
                whatThisIs + " If you weren't expecting this, ignore it: nothing happens unless you "
                        + "use the code.",
                EmailTemplate.Tone.INFO);

        dispatch(event.aggregateId(), "email", "staff_invite_issued",
                // The token is NOT in the log payload. notification_log is queried by support, and
                // a live invite code sitting in it is a credential anybody with console access can
                // redeem. The invite id is enough to correlate with bmp-salon.
                Map.of("email", event.inviteeEmail(), "role", event.role() == null ? "manager" : event.role(),
                        "salon", event.salonName() == null ? "" : event.salonName()),
                () -> emailSender.sendHtml(event.inviteeEmail(), subject, htmlBody, textBody));
    }

    private void handleStylistJoinDecided(StylistJoinRequestDecided event) {
        if (event.stylistEmail() == null || event.stylistEmail().isBlank()) {
            // WARN not ERROR: a stylist who registered without an email is a real, legitimate
            // state, unlike the support case where the publisher guarantees an address.
            log.warn("Join request {} was {} but we hold no email for stylist {} — they have not "
                    + "been told and will only find out by opening the app.",
                    event.aggregateId(), event.accepted() ? "accepted" : "declined", event.stylistId());
            return;
        }

        String greeting = event.stylistName() == null || event.stylistName().isBlank()
                ? "Hello," : "Hi " + event.stylistName() + ",";

        String subject = event.accepted()
                ? "You're on the team at " + event.salonName()
                : "About your request to join " + event.salonName();

        String textBody = event.accepted()
                ? greeting + "\n\n" + event.salonName() + " has added you to their team on BMP. "
                  + "Your schedule is in the app now, and customers can book you.\n\n"
                  + "Your profile, rating and reviews stay with you — they're yours, not the "
                  + "salon's.\n"
                : greeting + "\n\n" + event.salonName() + " didn't accept your request to join.\n\n"
                  + (event.decisionNote() == null ? "" : "They said: " + event.decisionNote() + "\n\n")
                  + "If that looks like a mistake — wrong branch, or a name they didn't recognise "
                  + "— you can send another request from the app.\n";

        String htmlBody = EmailTemplate.card(
                event.accepted()
                        ? "You've been added to " + event.salonName() + "."
                        : event.salonName() + " answered your request.",
                event.accepted() ? "You're on the team" : "Not accepted this time",
                event.accepted()
                        ? greeting + " " + event.salonName() + " has added you to their team. Your "
                          + "schedule is in the app and customers can book you from now on."
                        : greeting + " " + event.salonName() + " didn't accept your request to join.",
                java.util.List.of(EmailTemplate.Row.of("Salon", event.salonName())),
                // The owner's reason, in the callout, because on a decline it is the only content
                // that lets the stylist do anything differently.
                event.accepted() ? null : event.decisionNote(),
                event.accepted()
                        ? "Your profile, rating and reviews belong to you and travel with you if "
                          + "you ever change salons."
                        : "If that looks like a mistake, you can send another request from the app.",
                event.accepted() ? EmailTemplate.Tone.GOOD : EmailTemplate.Tone.ATTENTION);

        dispatch(event.stylistId(), "email", "stylist_join_decided",
                Map.of("email", event.stylistEmail(), "accepted", String.valueOf(event.accepted())),
                () -> emailSender.sendHtml(event.stylistEmail(), subject, htmlBody, textBody));
    }

    /**
     * A salon approved or declined leave. Session 49.
     *
     * <h2>Why this one matters more than most</h2>
     * Leave is the request people make plans around. A stylist who asked for four days in October
     * and hears nothing will either book the flights and not turn up, or cancel a trip they didn't
     * need to — both from the same silence.
     *
     * <p>The email leads with the DATES. "Your leave was approved" is nearly useless alone:
     * people have more than one request outstanding, and an email that doesn't say which forces
     * them back into the app, which is what it was meant to save.
     */

    /**
     * A stylist's diary changed. Session 53.
     *
     * <h2>The silence this ends</h2>
     * The customer was told at booking (Session 34) and the salon was told (Session 40). The
     * stylist found out by opening the app — including for a counter booking a manager took ten
     * minutes before it starts, which is the case where a message is worth most.
     *
     * <h2>There is no customer name in here, and there cannot be</h2>
     * {@link com.bmp.common.events.StylistAppointmentChanged} has no field for the customer's
     * name, phone, email or id, and none for the price. That is the Session 48/49 boundary
     * enforced by the event's SHAPE rather than by this method's restraint — a template somebody
     * writes next year cannot leak a field that was never carried.
     *
     * <p>So the message says what, when and how long. Who it is for, and what they are paying, is
     * the desk's business.
     *
     * <h2>A cancellation is not bad news to a stylist</h2>
     * It is a freed hour, and wording it as a loss ("unfortunately…") reads as blame for something
     * they did not do. Tone stays INFO, and the line is about the time being free again.
     */
    private void handleStylistAppointment(com.bmp.common.events.StylistAppointmentChanged event) {
        if (event.stylistEmail() == null || event.stylistEmail().isBlank()) {
            // WARN, not ERROR. A stylist who is not emailed still has the appointment on their
            // schedule in the app, so this is a missed convenience rather than a missed fact.
            log.warn("Booking {} was {} for stylist {} but we hold no email for them — they have "
                    + "not been told. It is on their schedule in the app either way.",
                    event.bookingRef(), event.change(), event.stylistId());
            return;
        }

        String greeting = event.stylistName() == null || event.stylistName().isBlank()
                ? "Hello," : "Hi " + event.stylistName() + ",";
        String when = formatWhen(event.startsAt());
        String salon = event.salonName() == null || event.salonName().isBlank()
                ? "your salon" : event.salonName();
        String services = event.serviceNames() == null || event.serviceNames().isEmpty()
                ? "an appointment"
                : String.join(", ", event.serviceNames());
        String howLong = event.durationMinutes() > 0 ? event.durationMinutes() + " min" : null;

        String subject;
        String headline;
        String lead;
        EmailTemplate.Tone tone;

        switch (event.change()) {
            case "moved" -> {
                // The previous time is the entire reason this message exists. Without it the
                // stylist reads a new time and has no way to know it replaced an old one.
                String was = event.previousStart() == null ? null : formatWhen(event.previousStart());
                subject = "Moved: " + services + " — now " + when;
                headline = "An appointment moved";
                lead = greeting + " " + services + " at " + salon
                        + (was != null ? " has moved from " + was : " has been moved")
                        + " to " + when + ".";
                tone = EmailTemplate.Tone.ATTENTION;
            }
            case "cancelled" -> {
                subject = "Cancelled: " + services + " on " + when;
                headline = "That time is free again";
                lead = greeting + " the " + services + " at " + salon + " on " + when
                        + " has been cancelled, so that slot is open again.";
                tone = EmailTemplate.Tone.INFO;
            }
            default -> {
                subject = "You're booked: " + services + " — " + when;
                headline = "You've been booked";
                lead = greeting + " " + services + " has been booked with you at " + salon
                        + " for " + when + ".";
                tone = EmailTemplate.Tone.GOOD;
            }
        }

        java.util.List<EmailTemplate.Row> rows = new java.util.ArrayList<>();
        rows.add(EmailTemplate.Row.of("What", services));
        rows.add(EmailTemplate.Row.of("When", when));
        if (howLong != null) rows.add(EmailTemplate.Row.of("How long", howLong));
        rows.add(EmailTemplate.Row.of("Where", salon));

        String textBody = lead + "\n\n"
                + "What: " + services + "\n"
                + "When: " + when + "\n"
                + (howLong != null ? "How long: " + howLong + "\n" : "")
                + "Where: " + salon + "\n\n"
                + "Your full day is in the BMP app under My schedule.\n";

        String htmlBody = EmailTemplate.card(
                subject, headline, lead, rows, null,
                "Your full day is in the app under My schedule. Customer details stay with the "
                + "front desk — ask them if you need to reach anybody.",
                tone);

        dispatch(event.stylistId(), "email", "stylist_appointment_" + event.change(),
                Map.of("email", event.stylistEmail(), "bookingRef", event.bookingRef()),
                () -> emailSender.sendHtml(event.stylistEmail(), subject, htmlBody, textBody));
    }

    private void handleStylistLeaveDecided(StylistLeaveDecided event) {
        if (event.stylistEmail() == null || event.stylistEmail().isBlank()) {
            log.warn("Leave {} was {} but we hold no email for stylist {} — they have not been "
                    + "told. Leave is planned around; this one is worth chasing.",
                    event.aggregateId(), event.approved() ? "approved" : "declined", event.stylistId());
            return;
        }

        String greeting = event.stylistName() == null || event.stylistName().isBlank()
                ? "Hello," : "Hi " + event.stylistName() + ",";

        // "12 Oct" for one day, "12–16 Oct" for a range. Never "12 Oct – 12 Oct".
        String dates = event.startsOn().equals(event.endsOn())
                ? event.startsOn().toString()
                : event.startsOn() + " to " + event.endsOn();
        String span = event.wholeDay() ? "All day" : "Part of the day";

        String subject = (event.approved() ? "Leave approved: " : "Leave not approved: ") + dates;

        String textBody = greeting + "\n\n"
                + (event.approved()
                        ? event.salonName() + " approved your leave for " + dates + ".\n\n"
                          + "Customers can't book you on those dates.\n"
                        : event.salonName() + " didn't approve your leave for " + dates + ".\n\n"
                          + (event.decisionNote() == null ? "" : "They said: " + event.decisionNote() + "\n\n")
                          + "You're still on the calendar for those dates.\n");

        String htmlBody = EmailTemplate.card(
                (event.approved() ? "Approved: " : "Not approved: ") + dates,
                event.approved() ? "Your leave is approved" : "Your leave wasn't approved",
                greeting + " " + event.salonName()
                        + (event.approved()
                                ? " approved your time off."
                                : " couldn't approve your time off."),
                java.util.List.of(
                        EmailTemplate.Row.of("Dates", dates),
                        EmailTemplate.Row.of("Time", span),
                        EmailTemplate.Row.of("Salon", event.salonName())),
                event.approved() ? null : event.decisionNote(),
                event.approved()
                        ? "Customers can't book you on those dates. If your plans change, you can "
                          + "hand the days back from the app."
                        // Said plainly: an unapproved request means they are STILL EXPECTED IN,
                        // and assuming otherwise is how a chair sits empty on a busy Saturday.
                        : "You're still on the calendar for those dates, so please don't make firm "
                          + "plans around them.",
                event.approved() ? EmailTemplate.Tone.GOOD : EmailTemplate.Tone.ATTENTION);

        dispatch(event.stylistId(), "email", "stylist_leave_decided",
                Map.of("email", event.stylistEmail(), "approved", String.valueOf(event.approved())),
                () -> emailSender.sendHtml(event.stylistEmail(), subject, htmlBody, textBody));
    }

    /**
     * A stylist was barred from BMP, or that bar was lifted. Session 51.
     *
     * <h2>This is the most serious message this service sends</h2>
     * It tells somebody they can no longer earn on the platform. Three things follow from that,
     * and each is a deliberate choice rather than a nicety:
     *
     * <ol>
     *   <li><b>The reason is shown in full</b>, in the callout. A bar with no explanation is one
     *       the stylist cannot contest and support cannot defend — and the first thing they will
     *       do is ask, so the answer should already be in their hand.</li>
     *   <li><b>It says what SURVIVES.</b> Profile, reviews, rating, work history: all intact. The
     *       immediate fear is that everything is gone, and leaving that unaddressed turns a
     *       reversible decision into somebody deleting their account.</li>
     *   <li><b>It says the decision can be reviewed.</b> Suspensions get made on incomplete
     *       information; a message that reads as final when it is reversible loses people who
     *       should not have been lost.</li>
     * </ol>
     */
    private void handleSuspensionChanged(StylistSuspensionChanged event) {
        if (event.stylistEmail() == null || event.stylistEmail().isBlank()) {
            // ERROR, not WARN, unlike the other stylist handlers. Being unable to tell somebody
            // they have been barred is materially worse than not telling them a leave request
            // was approved — they will discover it by finding they cannot be booked, with no
            // reason and nobody to ask.
            log.error("Stylist {} was {} but we hold NO EMAIL for them. They have not been told, "
                    + "and will discover it by finding they cannot be booked. Reason on file: {}",
                    event.aggregateId(), event.suspended() ? "SUSPENDED" : "reinstated",
                    event.reason());
            return;
        }

        String greeting = event.stylistName() == null || event.stylistName().isBlank()
                ? "Hello," : "Hi " + event.stylistName() + ",";

        String subject = event.suspended()
                ? "Your BMP stylist account has been suspended"
                : "Your BMP stylist account is active again";

        String textBody = event.suspended()
                ? greeting + "\n\n"
                  + "Your stylist account on BMP has been suspended, so customers can't book you "
                  + "at the moment.\n\n"
                  + (event.reason() == null ? "" : "Reason: " + event.reason() + "\n\n")
                  + "Your profile, your rating, your reviews and your work history are all still "
                  + "there — nothing has been deleted.\n\n"
                  + "If you think this is a mistake, reply to this email or contact "
                  + EmailTemplate.SUPPORT_EMAIL + " and we'll look at it again.\n"
                : greeting + "\n\n"
                  + "Your stylist account is active again. Customers can book you wherever you're "
                  + "still on a salon's team.\n\n"
                  + "If a salon removed you while this was in place, you'll need to ask them to "
                  + "add you back — we can't undo their decision for them.\n";

        String htmlBody = EmailTemplate.card(
                event.suspended()
                        ? "Your stylist account has been suspended."
                        : "Your stylist account is active again.",
                event.suspended() ? "Account suspended" : "You're active again",
                event.suspended()
                        ? greeting + " Your stylist account on BMP has been suspended, so "
                          + "customers can't book you at the moment."
                        : greeting + " Your stylist account is active again, and customers can "
                          + "book you wherever you're still on a salon's team.",
                event.suspended() && event.activeSalonCount() > 0
                        ? java.util.List.of(EmailTemplate.Row.of("Salons affected",
                                String.valueOf(event.activeSalonCount())))
                        : java.util.List.of(),
                // The reason, in the callout — the one thing on this page they can act on.
                event.reason(),
                event.suspended()
                        ? "Your profile, rating, reviews and work history are all still there — "
                          + "nothing has been deleted. If you think this is a mistake, reply to "
                          + "this email and we'll look at it again."
                        : "If a salon removed you while this was in place, ask them to add you "
                          + "back — we can't undo their decision for them.",
                event.suspended() ? EmailTemplate.Tone.ATTENTION : EmailTemplate.Tone.GOOD);

        dispatch(event.aggregateId(), "email", "stylist_suspension_changed",
                Map.of("email", event.stylistEmail(),
                       "suspended", String.valueOf(event.suspended())),
                () -> emailSender.sendHtml(event.stylistEmail(), subject, htmlBody, textBody));
    }

    /**
     * A salon ended a stylist's employment. Session 51.
     *
     * <h2>Why it names WHO did it</h2>
     * A stylist removed by BMP rather than by their own salon will otherwise ring the salon about
     * a decision the salon did not make — and the salon, who can see the removal but not the
     * reason behind an admin action, cannot help. One sentence prevents that call.
     *
     * <h2>And why it says they can work elsewhere</h2>
     * Being removed from a salon and being suspended from BMP are easy to confuse when you are on
     * the receiving end of either. The difference is the whole message: this one is not a ban.
     */
    private void handleRemovedFromSalon(StylistRemovedFromSalon event) {
        if (event.stylistEmail() == null || event.stylistEmail().isBlank()) {
            log.warn("Stylist {} was removed from salon {} but we hold no email — they will find "
                    + "out by opening the app.", event.aggregateId(), event.salonId());
            return;
        }

        boolean byAdmin = "admin".equalsIgnoreCase(event.actorKind());
        String greeting = event.stylistName() == null || event.stylistName().isBlank()
                ? "Hello," : "Hi " + event.stylistName() + ",";
        String salon = event.salonName() == null ? "the salon" : event.salonName();

        String subject = "You're no longer on the team at " + salon;

        String textBody = greeting + "\n\n"
                + (byAdmin
                        ? "BMP has removed you from the team at " + salon + ".\n\n"
                        : salon + " has removed you from their team.\n\n")
                + "You won't get new bookings there. Your profile, rating, reviews and work "
                + "history all stay with you — including the reviews you earned at " + salon + ".\n\n"
                + "You can join another salon whenever you like: search for them in the app and "
                + "send a request.\n"
                + (byAdmin
                        ? "\nIf you have questions about this, contact BMP support rather than the "
                          + "salon — this was our decision, not theirs.\n"
                        : "");

        String htmlBody = EmailTemplate.card(
                "You're no longer on the team at " + salon + ".",
                "Removed from " + salon,
                greeting + " " + (byAdmin ? "BMP has removed you from" : salon + " has removed you from")
                        + " their team, so you won't get new bookings there.",
                java.util.List.of(EmailTemplate.Row.of("Salon", salon)),
                null,
                // The reassurance that matters: this is NOT a ban, and nothing is lost.
                "Your profile, rating, reviews and work history stay with you — including the "
                + "reviews you earned here. You can join another salon whenever you like."
                + (byAdmin
                        ? " If you have questions, contact BMP support rather than the salon: this "
                          + "was our decision, not theirs."
                        : ""),
                EmailTemplate.Tone.ATTENTION);

        dispatch(event.aggregateId(), "email", "stylist_removed_from_salon",
                Map.of("email", event.stylistEmail(), "salonId", event.salonId().toString()),
                () -> emailSender.sendHtml(event.stylistEmail(), subject, htmlBody, textBody));
    }

    private void notifyOpsOfSalonSubmission(SalonStatusChanged event, boolean resubmission) {
        if (opsEmail == null || opsEmail.isBlank()) {
            // WARN, not INFO. This is a partner waiting on a decision nobody knows they owe —
            // it deserves the same volume as the coupon handler's equivalent, which had it right.
            log.warn("Salon {} is queued for review but bmp.notification.ops-email is not set, so "
                    + "NOBODY HAS BEEN TOLD. The owner is waiting. Set BMP_OPS_EMAIL.",
                    event.aggregateId());
            return;
        }

        String subject = (resubmission ? "[BMP] Salon RESUBMITTED — " : "[BMP] New salon request — ")
                + event.salonName();
        String body = (resubmission
                        ? "A salon that was previously rejected has been fixed and resubmitted.\n\n"
                        : "A new salon has applied to join BMP.\n\n")
                + "Salon:    " + event.salonName() + "\n"
                + "Salon ID: " + salonRef(event) + "\n"
                + "Owner:    " + (event.ownerName() == null ? "(name not on file)" : event.ownerName()) + "\n"
                + "Contact:  " + (event.ownerEmail() == null ? "(no email)" : event.ownerEmail())
                + "  " + (event.ownerPhone() == null ? "" : event.ownerPhone()) + "\n\n"
                + "Review it in the admin console. The owner has been emailed that we've received "
                + "it, so the clock is running from their point of view.\n";

        dispatch(event.ownerUserId(), "email", "salon_review_queued",
                Map.of("email", opsEmail, "salon", event.salonName()),
                () -> emailSender.send(opsEmail, subject, body));
    }

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

            /*
             * Session 56 — WhatsApp alongside SMS, at every booking moment.
             *
             * Before this, WhatsApp was wired at exactly ONE call site: the OTP. Every other
             * message — booked, moved, cancelled, salon closed — went email + SMS only. So the day
             * the Business account is approved and the flag flips, the channel would have carried
             * login codes and nothing a customer actually wants it for. Wiring it now means going
             * live is credentials and a flag, which is what "built but not integrated" should mean.
             *
             * The template NAME is passed, not the finished text, because that is what the Meta
             * API takes for a message to somebody who hasn't written to us in 24 hours — see
             * WhatsAppSender for why that shapes the interface. `sms` rides along as the rendered
             * body: the stub logs it, and a future SMS-fallback path (WhatsApp's "no account for
             * this number" is a permanent, expected failure) needs it.
             *
             * whatsappTemplate() maps our template code to the registered name, and returns null
             * for anything not yet approved — so an unapproved template is a silent skip rather
             * than a send that Meta rejects.
             */
            String waTemplate = whatsappTemplate(templateCode);
            if (waTemplate != null) {
                dispatch(customerId, "whatsapp", templateCode,
                        Map.of("phone", phone, "bookingRef", bookingRef, "template", waTemplate),
                        () -> whatsAppSender.send(phone, waTemplate, sms));
            }
        }
    }

    /**
     * Our template code → the name registered with Meta. Session 56.
     *
     * <h2>Why this mapping has to exist</h2>
     * WhatsApp will not deliver arbitrary text to somebody who has not messaged the business in
     * the last 24 hours. It must be a template registered with and approved by Meta, referenced
     * by ITS name — which is Meta's naming, not ours, and the two drift the moment somebody
     * renames one side. A lookup makes the correspondence explicit and reviewable in one place.
     *
     * <h2>null means "not approved yet", and that is a real state</h2>
     * Template approval is per-template and takes days. Returning null skips the WhatsApp send
     * for that message rather than attempting one Meta will reject — the customer still gets the
     * email and the SMS, and nothing appears broken while approvals trickle in.
     *
     * <p>The names below are the ones to REGISTER; none is approved yet, which is why the whole
     * channel is off by default. Keep this list and the Meta console in step — a code here with
     * no counterpart there is a message that silently never sends.
     */
    private static String whatsappTemplate(String templateCode) {
        return switch (templateCode) {
            case "booking_created"     -> "booking_confirmed";
            case "booking_cancelled"   -> "booking_cancelled";
            case "booking_rescheduled" -> "booking_moved";
            case "booking_completed"   -> "visit_complete";
            case "closure_affected"    -> "salon_closed";
            // Anything not listed has no approved template. Deliberately exhaustive-by-omission:
            // adding a new notification does NOT silently acquire a WhatsApp send it cannot make.
            default -> null;
        };
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
    /**
     * "in about 5 minutes (11:09 pm)" — how long a code has left, for a human. Session 47.
     *
     * <p>A DURATION, and only a duration. Session 48 removed the IST clock time that used to
     * follow in brackets: a person holding a code wants to know how long they have, and a
     * wall-clock deadline makes them compute that themselves. Two phrasings of one fact is not
     * twice as clear — it is a second thing to read on the first email a new user ever gets.
     *
     * <p>Rounds up, deliberately. Saying "in about 4 minutes" when 4:40 remains is safely
     * pessimistic; rounding down would tell someone they have longer than they do, which is the
     * error that costs them the code.
     */
    private String expiryPhrase(java.time.Instant expiresAt) {
        if (expiresAt == null) {
            // Should be unreachable — the OTP row always has one — but a missing expiry must not
            // produce "expires null". Vague and true beats precise and wrong.
            return "shortly";
        }
        long seconds = java.time.Duration.between(java.time.Instant.now(), expiresAt).getSeconds();

        if (seconds <= 0) return "any moment now — ask for a new code if it doesn't work";
        if (seconds < 90) return "in the next minute";

        long minutes = (seconds + 59) / 60;   // round UP; see the javadoc
        return "in the next " + minutes + " minutes";
    }

    /**
     * First letter upper-cased, so an expiry phrase can also open a sentence.
     *
     * <p>Exists so the phrase is written once and reused in both positions. Keeping two near-copies
     * of the same sentence is how "in about 5 minutes" and "in the next 5 minutes" end up in the
     * same email, months apart, with nobody noticing they disagree.
     */
    /**
     * The reference a human should read — BMPS001, falling back to the UUID. Session 48.
     *
     * <p>Salons created before V017 have no reference. Printing "null" in the one field an owner
     * is told to quote to support would be worse than the UUID it replaced, so the fallback is the
     * id: ugly, but true and usable.
     */
    private static String salonRef(SalonStatusChanged event) {
        return event.salonReference() != null && !event.salonReference().isBlank()
                ? event.salonReference()
                : String.valueOf(event.aggregateId());
    }

    /**
     * Last four digits, for a subject line. Session 65.
     *
     * <p>Enough to tell two of your own accounts apart at a glance; not enough to be worth reading
     * off somebody's lock screen. Falls back to the whole string for anything that isn't a
     * recognisable number rather than throwing — an unusual phone format must never be the reason
     * a login code fails to send.
     */
    private static String lastFour(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() >= 4 ? "\u2022\u2022\u2022\u2022" + digits.substring(digits.length() - 4) : phone;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

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
        /*
         * Session 47 — THE LOG ROW MUST NOT BE ABLE TO KILL THE SEND.
         *
         * notification_log.recipient_user_id is NOT NULL. Several events legitimately carry a
         * null user id: salon.status.changed does when the owner lookup failed, and any future
         * event about a person who has no account will too. Previously that meant
         * logService.log(...) threw a constraint violation BEFORE send.run() was ever reached —
         * so the email was never attempted, and the only trace was a warning about a database
         * error, which reads like a logging problem rather than "the customer got nothing".
         *
         * Bookkeeping failing must never cancel the thing being booked. So a missing recipient
         * falls back to a zero UUID, the same "logical ref, no physical FK" convention the OTP
         * handler already uses (see handleOtpRequested, which passes the otp row id because no
         * user row exists yet at signup). The template code is what tells a reader what the row
         * actually refers to.
         */
        UUID recipient = recipientUserId != null ? recipientUserId : UNKNOWN_RECIPIENT;
        if (recipientUserId == null) {
            log.warn("notification [{} / {}] has no recipient user id — logging against the "
                    + "placeholder id so the send still happens. The message itself is unaffected.",
                    channel, templateCode);
        }

        UUID logId = null;
        try {
            logId = logService.log(new LogRequest(recipient, channel, templateCode, payload)).id();
        } catch (Exception e) {
            // Still send. An unlogged delivery is a reporting gap; a blocked delivery is a
            // customer who never got their OTP.
            log.error("Could not write notification_log for [{} / {}] — SENDING ANYWAY.",
                    channel, templateCode, e);
        }

        try {
            send.run();
            if (logId != null) logService.markSent(logId);
        } catch (Exception e) {
            /*
             * Session 47: log the THROWABLE, not e.getMessage().
             *
             * This previously passed a String, so SLF4J printed one line and threw the cause
             * chain away. For SMTP that is precisely the information you need: MailSendException's
             * own message is often terse, while the cause underneath is
             * `AuthenticationFailedException: 535-5.7.8 Username and Password not accepted` or
             * `SMTPSendFailedException: 553 5.7.1 ... not owned by user`. Both name the exact fix;
             * neither was ever printed.
             *
             * "Mail silently doesn't arrive" was unfixable from the logs because of this line.
             */
            String reason = rootCauseOf(e);
            if (logId != null) logService.markFailed(logId, reason);
            log.error("NOTIFICATION SEND FAILED [{} / {}]: {}", channel, templateCode, reason, e);
        }
    }

    /**
     * The deepest cause, prefixed with its type.
     *
     * <p>The type is half the diagnosis: {@code AuthenticationFailedException} means the app
     * password is wrong or revoked, {@code SMTPSendFailedException} usually means the From address
     * isn't the authenticated account, and {@code ConnectException} means nothing reached the
     * server at all. Three completely different fixes that a bare message often fails to separate.
     */
    private static String rootCauseOf(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return cause.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
