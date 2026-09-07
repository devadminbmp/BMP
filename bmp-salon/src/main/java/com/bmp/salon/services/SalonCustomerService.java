package com.bmp.salon.services;

import com.bmp.salon.entities.SalonCustomer;
import com.bmp.salon.repositories.SalonCustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The salon's own customer book. Session 52, V026.
 *
 * <h2>What this exists for</h2>
 * Darshan: <i>"we should compulsorily have their data in our database — who are booking through
 * the salon. Remember, it's their own customer."</i>
 *
 * <p>So every counter booking passes through {@link #upsert}: a phone number and a name are
 * mandatory, and after the first visit the second one recognises them. Without this, walk-in trade
 * — which for most salons is the majority of trade — leaves no record of who came.
 *
 * <h2>Phone normalisation is the whole design</h2>
 * The unique index is {@code (salon_id, phone)}. If "98765 43210", "+919876543210" and
 * "9876543210" store as three different strings, they become three different customers and the
 * visit count never leaves 1 — the table would exist and do nothing. So exactly one normalised
 * form is ever written, and it happens here rather than being left to each caller.
 */
@Service
public class SalonCustomerService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SalonCustomerService.class);

    /** India. The counter types ten digits and means +91; nothing else in this product does. */
    private static final String DEFAULT_COUNTRY_CODE = "91";

    private final SalonCustomerRepository repo;

    public SalonCustomerService(SalonCustomerRepository repo) {
        this.repo = repo;
    }

    /**
     * One canonical stored form: digits only, country code included, no '+'.
     *
     * <p>Ten digits are treated as Indian and prefixed with 91. Twelve digits already starting
     * with 91 are left alone — this is the specific shape that produced the double-91 bug in the
     * auth flow (Session 43), where "+91" plus a number the user had already written with 91
     * became a 14-digit number that matched nothing.
     *
     * @throws ResponseStatusException 400 when there is no plausible phone number in the input
     */
    public static String normalisePhone(String raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A phone number is required.");
        }
        String digits = raw.replaceAll("[^0-9]", "");

        // 0-prefixed local dialling: 09876543210 -> 9876543210.
        if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() == 10) {
            digits = DEFAULT_COUNTRY_CODE + digits;
        }
        if (digits.length() < 10 || digits.length() > 15) {
            // Matches chk_salon_customer_phone in V026 — rejected here with a readable message
            // rather than as a constraint violation the receptionist cannot act on.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That doesn't look like a phone number. Enter 10 digits, e.g. 9876543210.");
        }
        return digits;
    }

    /**
     * Find this person at this salon, or create them. The counter's single entry point.
     *
     * <p>Idempotent on the phone number by design: a receptionist who books the same regular three
     * times today creates one record with three visits, not three records. The unique index is the
     * actual guarantee; this method is the well-behaved path to it.
     *
     * <p>Existing rows are REFRESHED, not overwritten — see {@link SalonCustomer#refresh}: a blank
     * email box is not an instruction to delete the email they gave last month.
     */
    @Transactional
    public SalonCustomer upsert(UUID salonId, String name, String rawPhone, String email, String notes) {
        String phone = normalisePhone(rawPhone);
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A name is required — it's what you'll call out when their turn comes.");
        }

        Optional<SalonCustomer> existing = repo.findBySalonIdAndPhone(salonId, phone);
        if (existing.isPresent()) {
            SalonCustomer c = existing.get();
            c.refresh(name, email, notes);
            log.debug("Matched existing salon customer {} at salon {} (visit {})",
                    c.getId(), salonId, c.getVisitCount() + 1);
            return repo.save(c);
        }

        SalonCustomer created = repo.save(
                new SalonCustomer(salonId, name.trim(), phone,
                        email == null || email.isBlank() ? null : email.trim(),
                        notes == null || notes.isBlank() ? null : notes.trim()));
        log.info("Created salon customer {} at salon {} (first visit)", created.getId(), salonId);
        return created;
    }

    /**
     * Count a visit. Called by the counter-booking flow AFTER the booking is actually created —
     * never before, or an abandoned booking inflates somebody's loyalty.
     */
    @Transactional
    public void recordVisit(UUID salonId, UUID customerId) {
        repo.findByIdAndSalonId(customerId, salonId).ifPresent(c -> {
            c.recordVisit(Instant.now());
            repo.save(c);
        });
    }

    /** Type-ahead at the counter. Blank query returns the regulars, most recent first. */
    @Transactional(readOnly = true)
    public Page<SalonCustomer> search(UUID salonId, String q, int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50));
        if (q == null || q.isBlank()) {
            return repo.findBySalonIdOrderByLastVisitAtDesc(salonId, pageable);
        }
        // A search for "98765" and one for "+98765" should find the same person.
        String needle = q.trim();
        if (needle.matches("[0-9+\\s-]+")) {
            needle = needle.replaceAll("[^0-9]", "");
            if (needle.length() == 10) needle = DEFAULT_COUNTRY_CODE + needle;
        }
        return repo.search(salonId, needle, pageable);
    }

    /**
     * Correct a record the desk already holds. Session 52.
     *
     * <h2>The phone number is NOT editable here</h2>
     * It is the identity of the row: the unique index is {@code (salon_id, phone)} and every past
     * booking was matched by it. Editing it in place would either collide with another customer's
     * record or silently re-point somebody's visit history at a different person.
     *
     * <p>A mistyped number is therefore a NEW customer, created by booking them again with the
     * right one. That is the honest outcome — the two rows really do represent what the salon
     * recorded — and merging them is a deliberate operation nobody has asked for yet.
     */
    @Transactional
    public SalonCustomer edit(UUID salonId, UUID customerId, String name, String email, String notes) {
        SalonCustomer c = requireAtSalon(salonId, customerId);
        if (name != null && name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A name is required — it's what you'll call out when their turn comes.");
        }
        /*
         * `refresh` ignores blanks, which is right for the BOOKING path (a receptionist in a hurry
         * leaves the email box empty and does not mean "delete their email"). On an explicit edit
         * screen, clearing a field IS the intent — so the note is cleared here when it arrives as
         * an empty string, and left alone when it arrives as null.
         */
        c.refresh(name, email, notes);
        if (notes != null && notes.isBlank()) c.clearNotes();
        return repo.save(c);
    }

    @Transactional(readOnly = true)
    public SalonCustomer requireAtSalon(UUID salonId, UUID customerId) {
        return repo.findByIdAndSalonId(customerId, salonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That customer isn't in this salon's book."));
    }
}
