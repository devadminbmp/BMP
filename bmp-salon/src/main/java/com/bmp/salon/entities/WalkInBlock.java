package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for salon_schema.walk_in_block.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "walk_in_block", schema = "salon_schema")
public class WalkInBlock {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "stylist_id", nullable = false)
    private UUID stylistId;
    @Column(name = "block_date", nullable = false)
    private LocalDate blockDate;
    @Column(name = "start_time", nullable = false)
    private String startTime;
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;
    @Column(name = "created_by_staff_id")
    private UUID createdByStaffId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalkInBlock() {} // JPA

    public WalkInBlock(UUID salonId, UUID stylistId, LocalDate blockDate, String startTime, int durationMinutes, UUID createdByStaffId) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.stylistId = stylistId;
        this.blockDate = blockDate;
        this.startTime = startTime;
        this.durationMinutes = durationMinutes;
        this.createdByStaffId = createdByStaffId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public UUID getStylistId() { return stylistId; }
    public LocalDate getBlockDate() { return blockDate; }
    public String getStartTime() { return startTime; }
    public int getDurationMinutes() { return durationMinutes; }
    public UUID getCreatedByStaffId() { return createdByStaffId; }
    public Instant getCreatedAt() { return createdAt; }
}
