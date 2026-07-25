package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for salon_schema.salon.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * @Setter otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon", schema = "salon_schema")
@Getter
public class Salon {

    @Id
    private UUID id;

    @Setter
    @Column(name = "name", nullable = false, length = 160)
    private String name;
    @Setter
    @Column(name = "location")
    private String location;
    @Setter
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Setter
    @Column(name = "stylist_assignment_strategy", nullable = false, length = 20)
    private String stylistAssignmentStrategy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Salon() {} // JPA

    public Salon(String name, String location, String status, String stylistAssignmentStrategy) {
        this.id = UuidV7.generate();
        this.name = name;
        this.location = location;
        this.status = status;
        this.stylistAssignmentStrategy = stylistAssignmentStrategy;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void touch() { this.updatedAt = Instant.now(); }
}
