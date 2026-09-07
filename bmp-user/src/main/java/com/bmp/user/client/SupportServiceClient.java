package com.bmp.user.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Support tickets live in bmp-admin. This is how bmp-user reaches them. Session 45.
 *
 * <h2>Why a hop instead of one service owning both</h2>
 * {@code AdminSecurityConfig} gives bmp-admin its own signing key and a {@code bmp-admin}
 * audience claim, so a customer or salon-owner token is rejected there <em>by design</em>. That
 * separation is worth keeping — it is what stops a stolen customer token being useful against the
 * staff console — so the user-facing door lives here and the storage stays there.
 *
 * <p>bmp-user authenticates the user and passes their verified identity; bmp-admin re-checks
 * ownership before returning anything. <b>This service is trusted to say who is asking, not to
 * say what they may see.</b> Getting that split wrong is how one service's parameter bug becomes
 * a cross-tenant read of every support ticket on the platform.
 *
 * <h2>These types are duplicated on purpose</h2>
 * The records below mirror bmp-admin's {@code AdminDtos}. Sharing them would mean putting support
 * DTOs in bmp-common, which every one of the thirteen services depends on — coupling all of them
 * to a schema two of them use. The cost is that a change in bmp-admin must be mirrored here; the
 * {@code ApiContractTest} guard exists for exactly that class of drift.
 */
@FeignClient(name = "bmp-admin-service",
        contextId = "supportServiceClient",
        configuration = com.bmp.user.config.FeignInternalKeyConfig.class)
public interface SupportServiceClient {

    /** One message in a thread. Internal staff notes are filtered out by bmp-admin. */
    record ThreadMessage(UUID id, String senderType, String message, Instant createdAt) {}

    /** A ticket as its raiser may see it — no assigned-staff routing, no internal notes. */
    /**
     * Mirrors bmp-admin's MyTicketResponse. The handling* fields (Session 64) tell the person
     * waiting who has their ticket — see that record for why silence in a support chat is not
     * neutral.
     *
     * This record must stay in step with the one in bmp-admin. Jackson ignores fields it does not
     * know about, so a field added there and forgotten here does not fail — it silently arrives as
     * null and the app shows nothing. That failure mode is the reason this comment exists.
     */
    record MyTicket(UUID id, String ticketRef, String category, String subject, String status,
                    UUID bookingId, boolean awaitingUs,
                    String handlingState, String handlingLabel, String handlingDetail,
                    String handlingDesk,
                    Instant createdAt, Instant resolvedAt,
                    List<ThreadMessage> messages) {}

    /**
     * Every identity field here is filled in by bmp-user from a verified JWT.
     *
     * <p>{@code requesterName} and {@code salonName} are SNAPSHOTS (Session 64, V013). They are
     * sent rather than looked up in bmp-admin because the console's queue lists fifty tickets at
     * a time — resolving each name live would be fifty cross-service calls per page load, any of
     * which can fail, and a failed name is indistinguishable in the UI from a ticket that never
     * had one. They are safe to trust here for the same reason {@code raisedByType} is: this
     * service derives them from the token, and the call travels the internal network as
     * ROLE_SERVICE.
     */
    record RaiseTicket(String raisedByType, UUID raisedById, UUID salonId, UUID bookingId,
                       String category, String subject, String description,
                       String requesterEmail, String requesterPhone,
                       String requesterName, String salonName) {}

    record UserReply(UUID callerId, UUID callerSalonId, String senderType, String body) {}

    @PostMapping("/api/v1/support-tickets/my")
    MyTicket raise(@RequestBody RaiseTicket req);

    @GetMapping("/api/v1/support-tickets/my")
    List<MyTicket> listMine(@RequestParam("callerId") UUID callerId,
                            @RequestParam(value = "callerSalonId", required = false) UUID callerSalonId);

    @GetMapping("/api/v1/support-tickets/my/{ticketId}")
    MyTicket getMine(@PathVariable("ticketId") UUID ticketId,
                     @RequestParam("callerId") UUID callerId,
                     @RequestParam(value = "callerSalonId", required = false) UUID callerSalonId);

    @PostMapping("/api/v1/support-tickets/my/{ticketId}/messages")
    MyTicket reply(@PathVariable("ticketId") UUID ticketId, @RequestBody UserReply req);
}
