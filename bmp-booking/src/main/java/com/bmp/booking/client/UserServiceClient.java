package com.bmp.booking.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

/**
 * Resolves who the customer is, so a booking can be told to them and about them.
 *
 * <h2>Why bmp-booking needs this (Session 34)</h2>
 * The rule this follows is already written down in bmp-rewards' copy of this interface:
 * {@code NotificationDispatcher} holds no clients, so <b>whoever emits an event is responsible
 * for putting a real address in it</b>. That constraint keeps the dispatcher a dumb delivery
 * mechanism instead of something that fans out into a dozen lookups per message.
 *
 * <p>bmp-booking held a {@code customer_id} and nothing else, so it could satisfy neither of the
 * two things that needed the answer: sending the customer a confirmation, and showing the salon
 * a name when a stylist calls in sick.
 *
 * <h2>Called exactly once per booking</h2>
 * At creation. The result is written to the booking row (V006), so {@code cancel} and
 * {@code salonTransition} publish their events with no outbound call at all. That is not an
 * optimisation — it is the difference between "you cannot cancel your appointment right now"
 * and a cancellation that works while bmp-user is restarting.
 *
 * <h2>A failure here does NOT fail the booking</h2>
 * The opposite of the {@code serviceMenu} call a few lines above it in {@code create}, and the
 * distinction is the whole design:
 *
 * <ul>
 *   <li><b>Price</b> cannot be guessed. A booking at an unverified price is one the salon has to
 *       honour, so an unreachable bmp-salon must fail the booking.</li>
 *   <li><b>Contact details</b> are not part of the agreement. A customer whose appointment was
 *       accepted but whose confirmation SMS never sent still has an appointment — they can see
 *       it in the app. Refusing the booking to protect the receipt would be backwards.</li>
 * </ul>
 *
 * <p>So {@code resolveCustomer} catches, logs at WARN with the booking ref, and carries on with
 * nulls. {@code NotificationDispatcher} then reports that it has nowhere to send.
 *
 * <p>Deliberately minimal: one method, four fields. A client that grows every field anyone might
 * want becomes a second, worse copy of bmp-user.
 */
@FeignClient(name = "bmp-user-service", configuration = com.bmp.booking.config.FeignInternalKeyConfig.class)
public interface UserServiceClient {

    /** Only what a notification and a salon desk need. bmp-user returns more; Jackson drops it. */
    record UserContact(UUID id, String phone, String name, String email) {}

    @GetMapping("/api/v1/users/{userId}")
    ResponseEntity<UserContact> getUserById(@PathVariable("userId") UUID userId);
}
