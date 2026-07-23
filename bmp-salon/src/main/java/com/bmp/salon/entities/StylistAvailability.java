package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for salon_schema.stylist_availability.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "stylist_availability", schema = "salon_schema")
public class StylistAvailability {

    @Id
    private UUID id;

    @Column(name = "stylist_id", nullable = false)
    private UUID stylistId;
    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "rule_type", nullable = false, length = 20)
    private String ruleType;
    @Column(name = "day_of_week")
    private int dayOfWeek;
    @Column(name = "specific_date")
    private LocalDate specificDate;
    @Column(name = "slot_type", nullable = false, length = 10)
    private String slotType;
    @Column(name = "start_time")
    private String startTime;
    @Column(name = "end_time")
    private String endTime;
    @Column(name = "blocks_booking", nullable = false)
    private boolean blocksBooking;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StylistAvailability() {} // JPA

    public StylistAvailability(UUID stylistId, UUID salonId, String ruleType, int dayOfWeek, LocalDate specificDate, String slotType, String startTime, String endTime, boolean blocksBooking) {
        this.id = UuidV7.generate();
        this.stylistId = stylistId;
        this.salonId = salonId;
        this.ruleType = ruleType;
        this.dayOfWeek = dayOfWeek;
        this.specificDate = specificDate;
        this.slotType = slotType;
        this.startTime = startTime;
        this.endTime = endTime;
        this.blocksBooking = blocksBooking;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getStylistId() { return stylistId; }
    public UUID getSalonId() { return salonId; }
    public String getRuleType() { return ruleType; }
    public int getDayOfWeek() { return dayOfWeek; }
    public LocalDate getSpecificDate() { return specificDate; }
    public String getSlotType() { return slotType; }
    public String getStartTime() { return startTime; }
    public String getEndTime() { return endTime; }
    public boolean isBlocksBooking() { return blocksBooking; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
