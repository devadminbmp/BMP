package com.bmp.salon.internal.entity;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * JPA entity for salon_schema.stylist_service.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "stylist_service", schema = "salon_schema")
public class StylistService {

    @Id
    private UUID id;

    @Column(name = "stylist_id", nullable = false)
    private UUID stylistId;
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "service_id", nullable = false)
    private UUID serviceId;
    @Column(name = "actual_duration_minutes", nullable = false)
    private int actualDurationMinutes;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "override_price_paise")
    private Money overridePricePaise;

    protected StylistService() {} // JPA

    public StylistService(UUID stylistId, UUID salonId, UUID serviceId, int actualDurationMinutes, Money overridePricePaise) {
        this.id = UuidV7.generate();
        this.stylistId = stylistId;
        this.salonId = salonId;
        this.serviceId = serviceId;
        this.actualDurationMinutes = actualDurationMinutes;
        this.overridePricePaise = overridePricePaise;

    }

    public UUID getId() { return id; }
    public UUID getStylistId() { return stylistId; }
    public UUID getSalonId() { return salonId; }
    public UUID getServiceId() { return serviceId; }
    public int getActualDurationMinutes() { return actualDurationMinutes; }
    public Money getOverridePricePaise() { return overridePricePaise; }
}
