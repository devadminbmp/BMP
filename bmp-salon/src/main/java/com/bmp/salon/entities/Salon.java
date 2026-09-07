package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.salon.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon", schema = "salon_schema")
@Getter
public class Salon {

    @Id
    private UUID id;

    @Setter
    @Column(name = "name", nullable = false, length = 160)
    private String name;
    @Setter
    @Column(name = "location")
    private String location;
    @Setter
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Setter
    @Column(name = "stylist_assignment_strategy", nullable = false, length = 20)
    private String stylistAssignmentStrategy;
    // ---- V011 (Session 40): the fields a customer needs to CHOOSE a salon ------------------
    //
    // Until now this entity had name, a PostGIS point, a status and two timestamps. Nothing
    // anybody would browse by. The frontend had been asking for all of this since Session 12
    // and getting a Zod parse failure, invisibly, because mocks were on.

    /** The neighbourhood, in words: "Indiranagar". `location` is for distance, not for reading. */
    @Setter
    @Column(name = "area", length = 120)
    private String area;

    /**
     * The street address.
     *
     * <p>SalonSignupSheet has collected this since Session 15 and discarded it, because the
     * create contract had nowhere to put it. The owner typed into a field that went nowhere.
     */
    @Setter
    @Column(name = "address")
    private String address;

    /**
     * When the owner first pressed Go Live. V018 (Session 48). NULL = never published.
     *
     * <p>Kept across suspension deliberately: a restored salon has published before, and greeting
     * it as brand new would be both wrong and a little insulting. See {@link #markWentLive()}.
     */
    @Column(name = "went_live_at")
    private Instant wentLiveAt;

    /**
     * Human-readable reference: BMPS001. V017 (Session 48).
     *
     * <p>A LABEL, never a key. The id stays the UUID everywhere the system routes on it; this is
     * what an owner reads off an email to support. Allocated by a Postgres sequence at insert —
     * see SalonService.create and V017 for why not count(*)+1.
     *
     * <p>No setter on purpose: a reference that can change is not a reference. It is written once,
     * by the repository's allocator, and never again.
     */
    @Column(name = "reference", length = 16, updatable = false)
    private String reference;

    /**
     * Assign the reference. Called ONCE, by SalonService.create, immediately after allocation.
     *
     * <p>Deliberately not a Lombok {@code @Setter}: this is not a settable property. Refusing to
     * overwrite an existing value makes that a rule the object enforces rather than a convention
     * the next caller has to know — a reference already printed in somebody's email must never
     * change underneath them.
     */
    /**
     * Stamp the first publication. Idempotent — a salon that is suspended and later goes live
     * again keeps its ORIGINAL date, because "when did you join BMP" has one answer.
     */
    public void markWentLive() {
        if (this.wentLiveAt == null) this.wentLiveAt = Instant.now();
    }

    public void assignReference(String ref) {
        if (this.reference != null) {
            throw new IllegalStateException(
                    "Salon " + id + " already has reference " + this.reference + " — it cannot be reassigned.");
        }
        this.reference = ref;
    }

    /**
     * Six-digit Indian PIN code. V016 (Session 48). Nullable for salons created before it.
     *
     * <p>Separate from {@link #address} on purpose: owners already type it into the address line,
     * where it is invisible to search and to any check that wants it as a value. See V016's header.
     *
     * <p>Length 6 matches the column and the CHECK constraint. Blank is normalised to null before
     * it reaches here — see SalonService.normalisePincode; the constraint accepts NULL but not "".
     */
    @Setter
    @Column(name = "pincode", length = 6)
    private String pincode;

    /** Owner-written. Nullable, and the UI must not invent one when it's absent. */
    @Setter
    @Column(name = "about")
    private String about;

    @Setter
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * Object-storage key when the cover image was uploaded through BMP; null when imageUrl is a
     * link the salon hosts. V015 (Session 44).
     *
     * <p>Replacing the cover must delete the old key first — see SalonService.imageStorageKey
     * for the two rules that govern every use of these columns.
     */
    @Setter
    @Column(name = "image_storage_key", length = 400)
    private String imageStorageKey;

    /**
     * Where booking alerts go. V011 (Session 40).
     *
     * <p>Until now the salon was <b>never told</b> a customer had booked — {@code booking.created}
     * reached the customer and nobody else, and the only way a salon found out was by having the
     * desk open (it polls every 60s). A booking made overnight was invisible until someone looked.
     *
     * <p>On the SALON, not resolved from the owner's login: the owner is a person, the bookings
     * inbox is a business function. Salons want the shop's shared address and the front-desk
     * handset, and when the person who created the account leaves, the alerts shouldn't leave too.
     *
     * <p>Both nullable — a salon that sets neither gets no alert, and the dispatcher logs that
     * rather than failing anything. The appointment is real either way.
     */
    @Setter
    @Column(name = "booking_notify_email", length = 160)
    private String bookingNotifyEmail;

    @Setter
    @Column(name = "booking_notify_phone", length = 20)
    private String bookingNotifyPhone;

    /**
     * NULL means "no reviews yet" and is <b>not</b> 0.00.
     *
     * <p>A new salon rendered as "0.0 ★" reads as terrible rather than new, and that lands on
     * the salon least able to absorb it. Every consumer renders null as "New".
     *
     * <p>Denormalised from bmp-review, like {@code stylist.overallRating} — a discovery list
     * renders dozens of rows and cannot afford a cross-service aggregate per row.
     */
    @Setter
    @Column(name = "rating", precision = 3, scale = 2)
    private java.math.BigDecimal rating;

    @Setter
    @Column(name = "review_count", nullable = false)
    private int reviewCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Salon() {} // JPA

    public Salon(String name, String location, String status, String stylistAssignmentStrategy) {
        this.id = UuidV7.generate();
        this.name = name;
        this.location = location;
        this.status = status;
        this.stylistAssignmentStrategy = stylistAssignmentStrategy;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() { this.updatedAt = Instant.now(); }
}
