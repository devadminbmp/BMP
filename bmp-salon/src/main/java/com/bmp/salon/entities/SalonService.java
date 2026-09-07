package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.salon_service.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon_service", schema = "salon_schema")
public class SalonService {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "name", nullable = false, length = 160)
    private String name;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "price_paise", nullable = false)
    private Money pricePaise;
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;
    @Column(name = "requires_stylist_assignment", nullable = false)
    private boolean requiresStylistAssignment;

    /**
     * V011 (Session 40). Groups the menu on the salon page: "Hair", "Skin", "Nails".
     *
     * <p>Nullable, and an ungrouped service goes under "Other" rather than being hidden — a
     * service a customer can't see is a service the salon can't sell.
     */
    // Plain field + explicit accessor below: this entity predates Lombok and doesn't import it.
    // Matching the file rather than introducing a second style in one class.
    @Column(name = "category", length = 60)
    private String category;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * V012 (Session 44) — when the salon retired this service. NULL = live and bookable.
     *
     * <p>There is deliberately no delete. Two tables hold real foreign keys to this row
     * ({@code stylist_service}, {@code salon_combo_item}) and {@code booking_service_item}
     * holds a cross-service logical ref with no FK at all — so a delete either fails loudly or,
     * worse, succeeds and quietly orphans every past booking that used it. See V012's header.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /**
     * V013 (Session 44) — what's included and what to expect. The menu previously carried only
     * the facts a BOOKING needs (name, price, duration); nothing that helps a customer DECIDE.
     */
    @Column(name = "description", length = 600)
    private String description;

    /**
     * V013 — a photo of the result. What a browser renders; always the readable address.
     *
     * <p>Since V015 (Session 44) this can be EITHER an image the owner uploaded through BMP or a
     * link to one they host elsewhere. Readers don't need to tell the difference — a URL is a
     * URL — which is exactly why the column didn't change shape when upload arrived.
     */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * Object-storage key when WE host the photo; null when {@link #imageUrl} points somewhere
     * the salon hosts. V015.
     *
     * <p>Two destructive rules depend on this and neither is optional:
     * <ul>
     *   <li><b>Replacing</b> a photo must delete the OLD key, or every re-upload leaks an object
     *       that nothing will ever reference again.</li>
     *   <li><b>Never</b> delete when this is null. That image belongs to the salon, not to us.</li>
     * </ul>
     */
    @Column(name = "image_storage_key", length = 400)
    private String imageStorageKey;

    protected SalonService() {} // JPA

    public SalonService(UUID salonId, String name, Money pricePaise, int durationMinutes, boolean requiresStylistAssignment) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.name = name;
        this.pricePaise = pricePaise;
        this.durationMinutes = durationMinutes;
        this.requiresStylistAssignment = requiresStylistAssignment;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public String getName() { return name; }
    public Money getPricePaise() { return pricePaise; }
    public int getDurationMinutes() { return durationMinutes; }
    public boolean isRequiresStylistAssignment() { return requiresStylistAssignment; }
    public Instant getCreatedAt() { return createdAt; }

    // V011 (Session 40) — menu grouping on the salon page.
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    // ── V012 (Session 44): editing and retiring ──────────────────────────────────────────────
    //
    // Editing price/duration is SAFE and needs no ceremony: booking_service_item freezes
    // name_snapshot / price_paise_snapshot / duration_shown_minutes at creation, so no edit here
    // can reach a booking that already exists. What the salon charges tomorrow and what it
    // charged last Tuesday are separate facts, and the schema already keeps them separate.

    public void setName(String name) { this.name = name; }
    public void setPricePaise(Money pricePaise) { this.pricePaise = pricePaise; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public void setRequiresStylistAssignment(boolean v) { this.requiresStylistAssignment = v; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getImageStorageKey() { return imageStorageKey; }
    public void setImageStorageKey(String imageStorageKey) { this.imageStorageKey = imageStorageKey; }

    public Instant getArchivedAt() { return archivedAt; }
    public boolean isArchived() { return archivedAt != null; }

    /**
     * Retire this service. Not a setter, for the same reason {@code OtpRequests.markConsumed}
     * isn't: there is one legitimate transition and a setter would invite arbitrary ones.
     * Idempotent — archiving twice keeps the first timestamp, which is the one that's true.
     */
    public void archive() {
        if (archivedAt == null) this.archivedAt = Instant.now();
    }

    /** Put it back on the menu. The counterpart to {@link #archive()} — and the reason this is
     *  an archive rather than a delete: retiring a service should be a decision you can revisit. */
    public void restore() {
        this.archivedAt = null;
    }
}
