package com.bmp.salon.internal.entity;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * JPA entity for salon_schema.salon_hours.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon_hours", schema = "salon_schema")
public class SalonHours {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;
    @Column(name = "open_time", nullable = false)
    private String openTime;
    @Column(name = "close_time", nullable = false)
    private String closeTime;

    protected SalonHours() {} // JPA

    public SalonHours(UUID salonId, int dayOfWeek, String openTime, String closeTime) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.dayOfWeek = dayOfWeek;
        this.openTime = openTime;
        this.closeTime = closeTime;

    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public int getDayOfWeek() { return dayOfWeek; }
    public String getOpenTime() { return openTime; }
    public String getCloseTime() { return closeTime; }
}
