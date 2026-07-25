package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.salon_policy.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon_policy", schema = "salon_schema")
@Getter
public class SalonPolicy {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Setter
    @Column(name = "template", nullable = false, length = 20)
    private String template;
    @Setter
    @Column(name = "free_cancel_hours", nullable = false)
    private int freeCancelHours;
    @Setter
    @Column(name = "late_grace_minutes", nullable = false)
    private int lateGraceMinutes;
    @Setter
    @Column(name = "require_prepayment", nullable = false)
    private boolean requirePrepayment;
    @Setter
    @Column(name = "slot_granularity_minutes", nullable = false)
    private int slotGranularityMinutes;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SalonPolicy() {} // JPA

    public SalonPolicy(UUID salonId, String template, int freeCancelHours, int lateGraceMinutes, boolean requirePrepayment, int slotGranularityMinutes) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.template = template;
        this.freeCancelHours = freeCancelHours;
        this.lateGraceMinutes = lateGraceMinutes;
        this.requirePrepayment = requirePrepayment;
        this.slotGranularityMinutes = slotGranularityMinutes;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() { this.updatedAt = Instant.now(); }
}
