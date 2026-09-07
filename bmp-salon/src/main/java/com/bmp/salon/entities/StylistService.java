package com.bmp.salon.entities;

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

    /*
     * ══════════════════════════════════════════════════════════════════════════════════════════
     * SESSION 67 — THERE IS DELIBERATELY NO MUTATOR ON THIS ENTITY
     * ══════════════════════════════════════════════════════════════════════════════════════════
     * Session 66 added `updateOverrides(duration, price)` so an owner could tune how long a
     * particular stylist takes. Darshan removed the concept:
     *
     *   "timing is standard for service and applicable all stylish"
     *
     * He is right. How long a haircut takes is a property of the haircut. Per-stylist timings do
     * not capture a real distinction so much as manufacture one, and then require somebody to
     * maintain stylists × services numbers forever — which nobody does, so they go stale and the
     * booking algorithm starts sizing appointments off figures no one believes.
     *
     * So this row now carries no editable state at all. It is pure membership: this stylist does
     * this service, or the row doesn't exist. `StylistCrudService.replaceServices` inserts and
     * deletes; nothing updates.
     *
     * `actualDurationMinutes` and `overridePricePaise` remain as COLUMNS because the table is
     * NOT NULL on the first and dropping columns is not something this codebase does casually.
     * They are written once, from the service's own duration, and read by nothing. A later
     * migration can remove them once no deployed build references the fields.
     *
     * If you are about to add a setter here: check whether the thing you want to vary is really a
     * property of the stylist, or a property of the service they perform.
     */
}
