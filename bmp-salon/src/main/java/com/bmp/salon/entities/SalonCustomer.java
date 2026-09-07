package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A SALON's own customer — not a BMP account. V026, Session 52.
 *
 * <h2>Whose customer this is</h2>
 * Darshan's framing, and it decides everything about this class: <i>"remember, it's their own
 * customer."</i> Somebody who read their phone number to a receptionist has not signed up to BMP,
 * has not agreed to hear from us, and is not ours. Creating a {@code users} row for them would be
 * the platform quietly acquiring people who never chose it.
 *
 * <p>What this IS: the salon's private contact card, scoped to that salon, so the second visit
 * recognises the first — "Priya, 4th visit" rather than a stranger every time.
 *
 * <h2>linked_user_id stays null until they choose otherwise</h2>
 * Nothing populates it by matching phone numbers on a schedule. Indian mobile numbers are
 * recycled, and silently attaching a stranger's salon visits to somebody's BMP account is a
 * privacy failure that cannot be undone once the history is merged.
 *
 * @see com.bmp.salon.services.SalonCustomerService for the upsert the counter actually uses
 */
@Entity
@Table(name = "salon_customer", schema = "salon_schema")
public class SalonCustomer {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /** Digits only, 10–15, normalised before it ever reaches here. See the CHECK in V026. */
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "linked_user_id")
    private UUID linkedUserId;

    /**
     * Denormalised deliberately. "Is this a regular?" gets asked at the counter with a person
     * waiting in front of you, and it must not require counting bookings across a service
     * boundary. Incremented by the counter-booking path, never recomputed on read.
     */
    @Column(name = "visit_count", nullable = false)
    private int visitCount;

    @Column(name = "last_visit_at")
    private Instant lastVisitAt;

    /** The salon's own note — "prefers Anjali", "allergic to ammonia". Never shown to BMP staff. */
    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SalonCustomer() {} // JPA

    public SalonCustomer(UUID salonId, String name, String phone, String email, String notes) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.notes = notes;
        this.visitCount = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Re-typed details on a later visit.
     *
     * <p>Blank values are IGNORED rather than written. A receptionist in a hurry types the phone
     * number, taps the matched card and leaves the email box empty — that is not an instruction to
     * erase the email they gave last month.
     */
    public void refresh(String name, String email, String notes) {
        if (name != null && !name.isBlank()) this.name = name.trim();
        if (email != null && !email.isBlank()) this.email = email.trim();
        if (notes != null && !notes.isBlank()) this.notes = notes.trim();
        this.updatedAt = Instant.now();
    }

    /**
     * Remove the salon's note. Separate from {@link #refresh} on purpose: that method treats a
     * blank as "no change", which is right while booking and wrong on an edit screen where an
     * emptied box IS the instruction.
     */
    public void clearNotes() {
        this.notes = null;
        this.updatedAt = Instant.now();
    }

    /** Called once per counter booking taken, not once per booking completed — see the note above. */
    public void recordVisit(Instant at) {
        this.visitCount = this.visitCount + 1;
        this.lastVisitAt = at;
        this.updatedAt = Instant.now();
    }

    /**
     * Associate with a real BMP account. Only ever called when the SAME person has proved the
     * number is theirs by signing up with it — never by a background matcher.
     */
    public void linkTo(UUID userId) {
        this.linkedUserId = userId;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public UUID getLinkedUserId() { return linkedUserId; }
    public int getVisitCount() { return visitCount; }
    public Instant getLastVisitAt() { return lastVisitAt; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
