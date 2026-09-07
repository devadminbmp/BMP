package com.bmp.admin.services;

import com.bmp.admin.client.BookingServiceClient;
import com.bmp.admin.client.NotificationServiceClient;
import com.bmp.admin.client.ReviewServiceClient;
import com.bmp.admin.client.UserServiceClient;
import com.bmp.admin.repositories.SupportTicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assemble everything BMP holds about one person. Session 60.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * THE TODO THIS CLOSES
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * {@code DataRequestService} carried: <i>"TODO(export): compile a machine-readable bundle across
 * user/booking/review and deliver it. Still manual."</i>
 *
 * <p>Erasure shipped in Session 56 and access did not, which is the wrong way round: erasure is the
 * rarer request and the one with a hard deadline, while access is what people actually ask for and
 * what a regulator samples. "Still manual" in practice meant an agent copying fields out of four
 * screens, which is slow, inconsistent between agents, and — the part that matters — quietly
 * incomplete, because nobody remembers the support tickets.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT COMPLETENESS MEANS HERE, AND WHERE IT STOPS
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Five sources: profile, bookings, reviews (including moderated ones), support tickets, and the
 * log of messages we sent (Session 64). A source
 * that FAILS is reported in the bundle as failed — never silently omitted. An export that quietly drops a section the service was down for is a
 * disclosure that claims to be complete and isn't, which is worse than an obviously partial one.
 *
 * <p>Not yet included, and named here so it is not mistaken for complete: notification and consent
 * history. bmp-notification does not record per-recipient delivery yet (the same gap behind the
 * bounce-state TODO in {@code ConsoleUserService}), so there is nothing to read. When it does, add a
 * fifth section here rather than leaving the bundle quietly short of it.
 *
 * <p>Deliberately NOT included: the audit log. It records what STAFF did, and its rows name staff
 * members — exporting it would disclose employees' identities and internal notes to a customer
 * under the banner of the customer's own data. Where an audit row concerns the person, the
 * underlying fact is already in one of the five sections.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THE BUNDLE GOES TO STAFF, NOT STRAIGHT TO THE CUSTOMER
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * The whole data-request flow is identity-verified by a person on purpose ("honouring a forged
 * export hands their history to whoever asked"). Emailing the bundle automatically to the address on
 * the account would route around that verification — and the commonest reason somebody requests an
 * export they did not make is that the account is compromised.
 *
 * <p>So this produces the bundle; the verified agent delivers it through the channel they verified.
 * That is a deliberate limit, stated rather than hidden, and it is recorded in the audit trail.
 */
@Service
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);

    private final UserServiceClient users;
    private final BookingServiceClient bookings;
    private final ReviewServiceClient reviews;
    private final SupportTicketRepository tickets;
    /** Session 64 — what we SENT them. The section the bundle's own notice admitted was missing. */
    private final NotificationServiceClient notifications;

    public DataExportService(UserServiceClient users, BookingServiceClient bookings,
                              ReviewServiceClient reviews, SupportTicketRepository tickets,
                              NotificationServiceClient notifications) {
        this.users = users;
        this.bookings = bookings;
        this.reviews = reviews;
        this.tickets = tickets;
        this.notifications = notifications;
    }

    /**
     * @param generatedAt when, so a bundle found on a laptop months later can be dated
     * @param sections    keyed by name. A value of {@code null} never appears — see
     *                    {@link #problems}, which is where a failed section is declared instead.
     * @param problems    empty on a clean run. NOT empty means the bundle is incomplete and says
     *                    so, in the bundle, where the recipient will see it.
     */
    public record ExportBundle(
            UUID subjectUserId,
            Instant generatedAt,
            String generatedByEmail,
            String notice,
            Map<String, Object> sections,
            List<String> problems) {}

    /**
     * Build it.
     *
     * <h2>Each source is caught separately</h2>
     * One dead service must not cost the requester the other four sections. A person waiting on a
     * legal deadline is better served by four-fifths of their data plus an explicit note about the
     * missing fifth than by an error and another week's wait.
     */
    public ExportBundle build(UUID subjectUserId, String staffEmail) {
        Map<String, Object> sections = new java.util.LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        collect(sections, problems, "profile", "your account details",
                () -> users.getUserById(subjectUserId).getBody());

        collect(sections, problems, "bookings", "your appointments",
                () -> bookings.byCustomer(subjectUserId));

        collect(sections, problems, "reviews", "reviews you wrote",
                () -> reviews.byAuthor(subjectUserId));

        /*
         * Tickets come from this service's own database, so they cannot fail the way the others can
         * — but they are the section a manual export always forgot, which is exactly why automating
         * this was worth doing.
         *
         * Only the ticket records, not the message thread: a support conversation contains the
         * agent's name and internal notes, and disclosing a colleague's words as part of a
         * customer's data export is a different decision from disclosing the customer's own.
         */
        collect(sections, problems, "support_tickets", "help requests you raised",
                () -> tickets.findByRaisedByIdOrderByCreatedAtDesc(subjectUserId).stream()
                        .map(t -> Map.of(
                                "reference", String.valueOf(t.getTicketRef()),
                                "subject", String.valueOf(t.getSubject()),
                                "status", String.valueOf(t.getStatus()),
                                "createdAt", String.valueOf(t.getCreatedAt())))
                        .toList());

        /*
         * WHAT WE SENT THEM. Session 64 — the fifth source.
         *
         * The bundle's own notice used to end "It does not yet include a log of messages we sent
         * you." That honesty was right: a bundle that silently omits a category is worse than one
         * that declares the omission, because the recipient cannot tell the difference.
         *
         * It is included now because a record that we emailed a given address at a given time about
         * a given booking is personal data we hold and process, and because it is frequently the
         * substance of the dispute: "you never told me the salon had closed" is answered by this log
         * and by nothing else. Note that it reports FAILED sends too — "we tried and it bounced" is
         * a materially different fact from "we never sent it", and the person asking deserves the
         * honest version rather than the flattering one.
         *
         * The message BODY is not included; see NotificationServiceClient for why (it names third
         * parties).
         */
        collect(sections, problems, "messages_we_sent", "emails and messages we sent you",
                () -> notifications.byRecipient(subjectUserId));

        if (!problems.isEmpty()) {
            log.error("Data export for {} is INCOMPLETE — {}. The bundle declares this; do not "
                    + "send it as a full disclosure without re-running the missing parts.",
                    subjectUserId, problems);
        }

        return new ExportBundle(
                subjectUserId,
                Instant.now(),
                staffEmail,
                problems.isEmpty()
                        // Session 64 — the caveat is gone because the gap is closed. A notice that
                        // still disclaims a section the bundle now contains would be its own bug:
                        // the recipient would assume something was withheld.
                        ? "This covers your account, bookings, reviews, help requests, and a log of "
                          + "the messages we sent you. Message contents are summarised by type "
                          + "rather than reproduced, because they can name other people."
                        : "PART OF THIS EXPORT IS MISSING — see 'problems'. Ask us to re-run it.",
                sections,
                problems);
    }

    /**
     * Run one source, and record a failure as a stated problem rather than an absent key.
     *
     * <p>{@code label} is the customer's word for the section, because the problems list is read by
     * the person receiving the bundle, not by an engineer: "we couldn't retrieve your appointments"
     * is actionable, "bookings: 503" is not.
     */
    private void collect(Map<String, Object> sections, List<String> problems,
                          String key, String label, java.util.concurrent.Callable<Object> source) {
        try {
            Object value = source.call();
            sections.put(key, value == null ? List.of() : value);
        } catch (Exception e) {
            log.error("Data export section '{}' failed ({})", key, e.toString());
            problems.add("We couldn't retrieve " + label + " this time.");
        }
    }
}
