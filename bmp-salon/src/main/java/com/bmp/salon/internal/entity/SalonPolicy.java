package com.bmp.salon.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.salon_policy.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon_policy", schema = "salon_schema")
public class SalonPolicy {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "template", nullable = false, length = 20)
    private String template;
    @Column(name = "free_cancel_hours", nullable = false)
    private int freeCancelHours;
    @Column(name = "late_grace_minutes", nullable = false)
    private int lateGraceMinutes;
    @Column(name = "require_prepayment", nullable = false)
    private boolean requirePrepayment;
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

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public String getTemplate() { return template; }
    public int getFreeCancelHours() { return freeCancelHours; }
    public int getLateGraceMinutes() { return lateGraceMinutes; }
    public boolean isRequirePrepayment() { return requirePrepayment; }
    public int getSlotGranularityMinutes() { return slotGranularityMinutes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // Setters for mutable fields
    public void setTemplate(String template) { this.template = template; }
    public void setFreeCancelHours(int freeCancelHours) { this.freeCancelHours = freeCancelHours; }
    public void setLateGraceMinutes(int lateGraceMinutes) { this.lateGraceMinutes = lateGraceMinutes; }
    public void setRequirePrepayment(boolean requirePrepayment) { this.requirePrepayment = requirePrepayment; }
    public void setSlotGranularityMinutes(int slotGranularityMinutes) { this.slotGranularityMinutes = slotGranularityMinutes; }

    // Update timestamp on mutation
    public void touch() { this.updatedAt = Instant.now(); }
}
