package com.bmp.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** BMP-25 DTOs — booking_schema.booking + booking_service_item + booking_events. */
public final class BookingDtos {
    private BookingDtos() {}

    /**
     * One service on a booking request.
     *
     * <h2>⚠️ Session 30: {@code nameSnapshot}, {@code pricePaise} and {@code durationMinutes}
     * ARE IGNORED. Do not add logic that reads them.</h2>
     * They are still accepted so existing clients don't break, but {@code BookingService.create}
     * now fetches all three from bmp-salon's service menu and writes THOSE. Whatever arrives in
     * these fields is discarded.
     *
     * <p>Until then this record's own javadoc said production "should look these up live from
     * bmp-salon-service instead of trusting the client — TODO(Phase 3)". It didn't. The
     * consequences of trusting them:
     * <ul>
     *   <li><b>Price:</b> a hand-rolled POST booked a ₹4,500 service for ₹1. The salon is left
     *       honouring a price nobody at the salon ever set.</li>
     *   <li><b>Duration:</b> worse, because it also defeated the availability check. Understate
     *       a 120-minute service as 15 and bmp-salon is asked "is there a 15-minute gap?" — yes,
     *       so the booking is accepted, then overruns the next three appointments.</li>
     * </ul>
     *
     * <p>What a client legitimately supplies is a set of CHOICES — which service, which stylist,
     * what time. Anything with a value attached is the server's to determine.
     *
     * <p>The fields should be deleted once no client sends them. Left in place for now because
     * removing them turns an ignored field into a 400 for anyone on an older build.
     *
     * @param nameSnapshot    IGNORED — resolved from the salon's menu
     * @param pricePaise      IGNORED — resolved from the salon's menu
     * @param durationMinutes IGNORED — resolved from the salon's menu
     */
    /*
     * Session 42: `@Deprecated` removed from the three components below.
     *
     * javac warned "@Deprecated annotation has no effect on this variable declaration". It was
     * right, and the warning is worth understanding rather than suppressing: on a record
     * component the annotation lands wherever its @Target allows, and @Deprecated does NOT
     * include RECORD_COMPONENT. So it was silently attaching to the constructor PARAMETER,
     * where it means nothing — no caller was ever warned.
     *
     * The intent was never a compiler warning anyway: these fields are ACCEPTED and IGNORED, so
     * flagging every caller would be noise for something they're allowed to keep sending. The
     * javadoc above already says it, and BookingService.create is where it's enforced. A
     * mechanism that looks like it warns and doesn't is worse than plain prose.
     */
    public record ItemRequest(
        @NotNull UUID serviceId, UUID stylistId, @NotBlank String selectionType,
        @NotNull Instant start,
        String nameSnapshot,
        long pricePaise,
        int durationMinutes
    ) {}

    /**
     * @param couponCode optional. Session 22: bmp-booking sends the CODE, never a discount
     *                   amount — bmp-rewards decides what it's worth. A code that turns out to
     *                   be invalid FAILS the booking rather than being silently ignored: the
     *                   customer chose to book at a price they were shown, and charging them
     *                   full price instead is something they'd only notice on their statement.
     */
    public record CreateBookingRequest(
        @NotNull UUID salonId, @NotNull UUID customerId,
        @NotEmpty List<@Valid ItemRequest> items,
        String couponCode
    ) {}

    public record ItemResponse(
        UUID id, UUID serviceId, UUID assignedStylistId, String selectionType,
        Instant serviceStart, Instant serviceEnd, String nameSnapshot, long pricePaiseSnapshot,
        int durationShownMinutes, String itemStatus
    ) {}

    /**
     * @param grossAmountPaise the basket BEFORE any discount
     * @param discountPaise    what the coupon took off
     * @param finalAmountPaise what the customer actually pays
     *
     * <p>All three are returned so a confirmation screen can show the saving rather than just a
     * total. "₹900, you saved ₹300" is worth considerably more to a customer than "₹900", and
     * making the client subtract two numbers invites it to get the arithmetic wrong.
     */
    public record BookingResponse(
        UUID id, String bookingRef, UUID salonId, UUID customerId, String status,
        long finalAmountPaise, long totalRefundedPaise, Instant createdAt,
        List<ItemResponse> items,
        UUID couponId, long grossAmountPaise, long discountPaise,
        /*
         * Session 35. Added so the SALON's history list can show a person rather than a UUID —
         * the same problem the day view had, on the screen a manager uses to look things up
         * afterwards.
         *
         * These also appear on the CUSTOMER's own "my bookings" response, because both lists
         * come through toResponse(). That is fine and mildly redundant: a customer seeing their
         * own name and their own masked number learns nothing they didn't have. Splitting into
         * two response records to avoid it would mean two shapes that drift, which costs more
         * than the redundancy.
         *
         * customerPhone is MASKED here for the same reason as on the day view — a history list
         * showing full numbers is a customer database with paging. The real number comes from
         * POST /bookings/{id}/reveal-contact, which writes it down.
         *
         * Null for bookings made before V006, or made while bmp-user was unreachable.
         */
        String customerName, String customerPhone
    ) {}

    public record PagedBookings(List<BookingResponse> content, int page, int size, long totalElements) {}

    /** @param reason optional from a customer, REQUIRED from a salon — the customer is shown it. */
    public record CancelRequest(String reason) {}

    // ---- Session 37: cancelling with terms, and rescheduling --------------------------------

    /**
     * What cancelling would cost, before committing to it.
     *
     * <p>Produced by the identical calculation the real cancellation runs, so the number shown
     * and the number charged cannot disagree. Two implementations of "what does this cost" is
     * two answers, and the one that quietly wins is whichever runs second.
     *
     * @param feeBps      basis points of the booking total. 0 = free.
     * @param feePaise    the rupee figure, integer paise
     * @param refundPaise what would come back. <b>Advisory today</b> — there are no payments yet
     *                    (B2/Razorpay), so nothing has actually been taken. Shown so the number
     *                    is honest once payments land, not to imply money is moving now.
     * @param reason      free | late | no_notice | salon_cancelled | no_policy
     * @param hoursNotice hours until the ORIGINAL appointment. Negative if it has passed.
     * @param explanation the salon's terms in a sentence, composed server-side so three clients
     *                    don't each write their own wording of someone's cancellation policy
     */
    public record CancelPreviewResponse(
        int feeBps, long feePaise, long refundPaise,
        String reason, long hoursNotice, String explanation
    ) {}

    /**
     * One service's new time.
     *
     * @param itemId   which service on the booking is moving. Every item must be listed —
     *                 a partial payload would split one appointment across two days.
     * @param start    the new start. The END is derived from the STORED duration, never from
     *                 the client and never re-fetched from today's menu.
     * @param stylistId optional. Null keeps whoever is already assigned; the availability
     *                  algorithm may still reassign if they're busy at the new time.
     */
    public record RescheduleItem(@NotNull UUID itemId, @NotNull Instant start, UUID stylistId) {}

    /**
     * @param reason optional from a customer, REQUIRED from a salon — "your appointment moved"
     *               with no explanation is how a salon loses somebody.
     */
    public record RescheduleRequest(@NotEmpty List<@Valid RescheduleItem> items, String reason) {}

    /**
     * Whether this booking can still be moved by the CUSTOMER, and why not if it can't.
     *
     * <p>Asked before showing a Reschedule button, so the app never offers an action that is
     * about to 409. The refusal is the salon's own terms in plain words: a customer told
     * "changes need 24 hours' notice" can act on it — usually by cancelling while it's still
     * free — where "you can't reschedule" just produces a support ticket.
     */
    public record RescheduleEligibility(
        boolean allowed, String refusal, int noticeHours, int used, int max
    ) {}

    // ---- Session 16: manager desk -------------------------------------------------------

    /**
     * One service block on the salon's day — the unit a manager actually works with.
     *
     * <p>Flattened deliberately: a booking of "cut at 11:00 with Ravi + colour at 11:45 with
     * Meera" is two rows here, because those are two things happening at two times on two
     * people's calendars. {@code bookingRef} ties them back together for the customer-facing
     * conversation ("that's booking BMP-4K2P").
     */
    /**
     * @param customerName   Session 34. The desk carried {@code customerId} — a bare UUID — and
     *                       nothing else, so a manager whose stylist had called in sick had no
     *                       name to apologise to. Null for bookings made before V006, or made
     *                       while bmp-user was unreachable; the UI falls back to "Customer".
     * @param customerPhone  MASKED ({@code 98765 4••••}). The salon can see that a number exists
     *                       and match it against a walk-in, without the day view becoming a
     *                       downloadable customer list. Two reasons that matters: a salon that
     *                       can harvest BMP's customers can take them off-platform — that is the
     *                       commission walking out the door — and under DPDP, BMP is the data
     *                       fiduciary either way. Revealing the full number is a separate,
     *                       audited action; see docs/PENDING_WORK.md.
     */
    public record ScheduleEntryResponse(
        UUID bookingId, String bookingRef, String bookingStatus, UUID customerId,
        String customerName, String customerPhone,
        UUID itemId, String serviceName, UUID assignedStylistId, String selectionType,
        Instant start, Instant end, int durationMinutes, long pricePaise, String itemStatus
    ) {}

    /**
     * Counts for the top of the desk. Money is summed in integer paise (never floats), and
     * {@code expectedRevenuePaise} EXCLUDES cancelled and no-show bookings — showing money the
     * salon isn't going to receive would be worse than showing nothing.
     */
    public record SalonDaySummaryResponse(
        String date, int totalBookings, int completed, int cancelled, int noShow, int upcoming,
        long expectedRevenuePaise
    ) {}

    public record SalonDayResponse(SalonDaySummaryResponse summary, List<ScheduleEntryResponse> entries) {}

    /** Optional free-text note recorded on the booking's append-only event trail. */
    public record SalonActionRequest(String note) {}

    // ---- Session 35: reaching the customer ------------------------------------------------

    /**
     * Why the salon needs the customer's actual number.
     *
     * <p>Required, and constrained to a known set — see {@code ContactRevealReason}. A free-text
     * box would be filled with "." within a week; an unexplained reveal is one nobody can review
     * later, and reviewing them is the entire point of recording them.
     *
     * @param reason one of {@code running_late}, {@code stylist_unavailable},
     *               {@code confirm_booking}, {@code customer_unreachable}, {@code other}
     * @param note   optional detail. Required by the service when reason is {@code other},
     *               because "other" on its own says nothing.
     */
    public record ContactRevealRequest(@NotBlank String reason, String note) {}

    /**
     * The customer's real contact details, handed over once and written down.
     *
     * <p>Deliberately does NOT include the email. The salon asked to make a phone call; handing
     * over an email address as well because it happened to be in the same row is how a
     * "let them ring the customer" feature becomes a mailing list. If salons ever need to email
     * customers, that is its own decision with its own consent question.
     *
     * @param phone full, unmasked. Null if BMP holds no number — see {@code revealedAt}'s note.
     * @param name  the customer's name, so the salon opens the call correctly
     * @param revealedAt when this was recorded, echoed back so the UI can say "you looked this
     *                   up just now" rather than implying the salon holds it permanently
     */
    public record ContactRevealResponse(String phone, String name, Instant revealedAt) {}

    // ---- Session 36: one customer, as this salon knows them --------------------------------

    /**
     * What a salon has learned about one customer <b>from their visits to that salon</b>.
     *
     * <h2>The boundary this record enforces</h2>
     * Every number here is computed over {@code salon_id = <caller's salon> AND customer_id =
     * <this customer>}. None of it reflects anything the customer did at another salon. That is
     * not a nicety: where a customer went last month is another salon's commercial data and the
     * customer's private business, and a marketplace that leaks it to its own suppliers is one
     * that has stopped being trustworthy to both sides.
     *
     * <h2>Why cancelled and no-show are separate numbers</h2>
     * They are separate facts about a person. Cancelling is a customer using the product
     * correctly — the salon got the slot back. A no-show cost the salon an empty chair. Rolling
     * them into one "didn't attend" figure would let a considerate customer read as an unreliable
     * one, and it is exactly the number a salon would act on.
     *
     * @param customerName   from the V006 snapshot; null for pre-V006 bookings
     * @param customerPhone  MASKED. Reveal is a separate audited call.
     * @param totalBookings  every booking, in any status
     * @param completedVisits actual visits. The gap between this and totalBookings is the point.
     * @param totalSpentPaise summed over COMPLETED only — money that was actually earned, not
     *                        money that was once scheduled. Integer paise.
     * @param usualStylistId the stylist on the most of their completed items, or null on a tie
     *                       or with no completed visits. Null is honest; picking a winner from a
     *                       tie invents a preference the customer never expressed.
     * @param recent         their most recent bookings at this salon, newest first
     */
    public record CustomerAtSalonResponse(
        UUID customerId,
        String customerName,
        String customerPhone,
        long totalBookings,
        long completedVisits,
        long cancelledCount,
        long noShowCount,
        long totalSpentPaise,
        Instant firstVisit,
        Instant lastVisit,
        UUID usualStylistId,
        PagedBookings recent
    ) {}

    public record EventResponse(String eventType, String actorType, UUID actorId, Instant createdAt) {}

    public record ErrorResponse(String error, String message) {}

    /**
     * Session 8 (availability algorithm) — a single busy window on a stylist's day, from
     * either an active booking_service_item or an unexpired slot_lock. bmp-salon subtracts
     * these from the stylist's working hours to compute free slots.
     */
    public record BusyWindow(LocalTime start, LocalTime end, String source) {}

    public record BusyWindowsResponse(List<BusyWindow> windows) {}
}
