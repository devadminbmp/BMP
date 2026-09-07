package com.bmp.admin.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What we sent this person, for the DPDP/GDPR export. Session 64.
 *
 * <h2>Why the export was incomplete without it</h2>
 * The export covered profile, bookings, reviews and support tickets, and its own notice said so
 * plainly: <em>"It does not yet include a log of messages we sent you."</em> That honesty was the
 * right call at the time — a bundle that silently omits a category is worse than one that declares
 * the omission, because the person receiving it cannot tell the difference.
 *
 * <p>But under the DPDP Act a data principal's right of access covers personal data we PROCESS
 * about them, and a record that we emailed a given address at a given time, about a given booking,
 * is personal data we hold. The messages are also frequently the substance of a complaint: "you
 * never told me the salon had closed" is answered by this log and by nothing else.
 *
 * <h2>What is deliberately NOT returned</h2>
 * The rendered message body. `notification_log.payload` holds the template variables, which can
 * include another party's details — a booking notification names the salon and the stylist, and a
 * closure notice can carry a manager's phone number. The export gives the person what we sent them,
 * WHEN, on WHICH channel, and whether it arrived. It does not hand over a third party's contact
 * details because they happened to appear in an email.
 *
 * <p>The same reasoning already governs support tickets in this bundle: the ticket records are
 * exported, the message thread is not, because the thread contains an agent's words and internal
 * notes. Disclosing somebody else's data as part of a subject's export is a different decision from
 * disclosing the subject's own, and it is not one this endpoint gets to make silently.
 */
@FeignClient(name = "bmp-notification-service", contextId = "adminNotificationClient")
public interface NotificationServiceClient {

    /**
     * @param status 'queued' | 'sent' | 'failed'. Included because "we tried and it bounced" is a
     *               materially different fact from "we never sent it", and a person disputing
     *               whether they were told deserves the honest version rather than the flattering
     *               one.
     */
    record NotificationEntry(
            UUID id,
            String channel,
            String templateCode,
            String status,
            Instant createdAt,
            Instant sentAt,
            Instant deliveredAt) {}

    @GetMapping("/api/v1/notifications/recipient/{recipientUserId}/export")
    List<NotificationEntry> byRecipient(@PathVariable UUID recipientUserId);
}
