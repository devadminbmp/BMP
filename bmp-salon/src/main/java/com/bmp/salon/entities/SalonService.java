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
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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
}
