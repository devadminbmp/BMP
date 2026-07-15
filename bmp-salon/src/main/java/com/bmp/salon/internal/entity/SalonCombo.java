package com.bmp.salon.internal.entity;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.salon_combo.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon_combo", schema = "salon_schema")
public class SalonCombo {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "name", nullable = false, length = 160)
    private String name;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "price_paise", nullable = false)
    private Money pricePaise;
    @Column(name = "allows_addons", nullable = false)
    private boolean allowsAddons;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SalonCombo() {} // JPA

    public SalonCombo(UUID salonId, String name, Money pricePaise, boolean allowsAddons) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.name = name;
        this.pricePaise = pricePaise;
        this.allowsAddons = allowsAddons;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public String getName() { return name; }
    public Money getPricePaise() { return pricePaise; }
    public boolean isAllowsAddons() { return allowsAddons; }
    public Instant getCreatedAt() { return createdAt; }
}
