package com.bmp.rewards.listeners;

import com.bmp.common.events.BookingCompleted;
import com.bmp.common.kafka.KafkaTopics;
import com.bmp.rewards.services.ReferralService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Settles a referral when the referred customer actually turns up. Session 64.
 *
 * <h2>The missing link</h2>
 * Everything on either side of this class already existed:
 *
 * <ul>
 *   <li>{@code referral.completed_at} — V002, commented "set on referee's FIRST COMPLETED VISIT"</li>
 *   <li>{@code ReferralService.completeOnFirstVisit(...)} — fraud and expiry guards, fully written</li>
 *   <li>{@code BookingCompleted} — published by bmp-booking on every completed appointment</li>
 * </ul>
 *
 * And nothing connected them. bmp-rewards had no Kafka listener at all, so the method had no caller,
 * so referrals accumulated attribution and paid nothing. ReferralService's own javadoc said so:
 * <em>"Do not advertise a referral programme until this is wired."</em>
 *
 * <p>The eighth instance in this codebase of the same lesson: <b>a service method with no caller is
 * a design document that compiles.</b>
 *
 * <h2>Why completion, and not signup or payment</h2>
 * Signup is free to manufacture; paying for it pays for accounts rather than customers, and invites
 * exactly the farming a referral programme cannot afford. Payment is closer, but a paid booking can
 * still be cancelled or refunded, and clawing back a wallet credit afterwards is unpleasant and
 * usually impossible once it has been spent.
 *
 * <p>Completion is the first moment the value is real and settled — and the moment the referrer's
 * recommendation actually paid off, which is the thing being thanked.
 *
 * <h2>At-least-once, so the payout must be idempotent</h2>
 * Kafka redelivers: a consumer restart, a partition rebalance, or an outbox row retried after a
 * timeout all replay this event. The guard is deliberately NOT here — it is
 * {@code Referral.markCompleted()}, which returns false once {@code completed_at} is set and stops
 * the payout before any wallet is touched.
 *
 * <p>On the entity rather than in this listener, because a second consumer added later would have
 * to remember to re-implement a check placed here, and would not.
 */
@Component
public class BookingCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(BookingCompletedListener.class);

    private final ReferralService referrals;

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            /*
             * Events gain fields over time. A consumer that dies on an unknown property turns an
             * additive, backward-compatible producer change into an outage in an unrelated service —
             * and the service that breaks is never the one that was changed, which makes it a
             * genuinely nasty afternoon.
             */
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public BookingCompletedListener(ReferralService referrals) {
        this.referrals = referrals;
    }

    @KafkaListener(topics = KafkaTopics.EVENTS, groupId = "bmp-rewards-service")
    public void onEvent(@Payload String payload, @Header("eventType") String eventType) {
        /*
         * One listener filtering by type — the shape NotificationDispatcher already uses. Every
         * service shares the events topic, so this method sees every event on the platform and has
         * to ignore almost all of them cheaply.
         */
        if (!"booking.completed".equals(eventType)) return;

        try {
            BookingCompleted event = mapper.readValue(payload, BookingCompleted.class);
            referrals.completeOnFirstVisit(event.customerId());
        } catch (Exception e) {
            /*
             * Swallowed, deliberately.
             *
             * Rethrowing makes Kafka redeliver, and redelivering a message that fails
             * DETERMINISTICALLY — a malformed payload, a bug in the payout — blocks the partition
             * and stops every later booking.completed from being processed. A referral that failed
             * to settle is a support ticket; a stuck partition is an outage.
             *
             * The attribution row survives either way, so a failed settlement can be replayed by
             * hand. That is the entire reason a referral is RECORDED at attribution time rather
             * than computed at payout time.
             */
            log.error("Could not settle a referral for a completed booking — the referral row is "
                    + "intact and can be settled manually. ({})", e.toString(), e);
        }
    }
}
