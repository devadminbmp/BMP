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

    /** Owner-written. Nullable, and the UI must not invent one when it's absent. */
    @Setter
    @Column(name = "about")
    private String about;

    @Setter
    @Column(name = "image_url", length = 500)
    private String imageUrl;

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
