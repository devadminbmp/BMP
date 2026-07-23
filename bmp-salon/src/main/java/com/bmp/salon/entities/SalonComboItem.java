package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import java.util.UUID;

/**
 * JPA entity for salon_schema.salon_combo_item.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "salon_combo_item", schema = "salon_schema")
public class SalonComboItem {

    @Id
    private UUID id;

    @Column(name = "combo_id", nullable = false)
    private UUID comboId;
    @Column(name = "service_id", nullable = false)
    private UUID serviceId;
    @Column(name = "requires_specialist", nullable = false)
    private boolean requiresSpecialist;
    @Column(name = "sequence", nullable = false)
    private int sequence;

    protected SalonComboItem() {} // JPA

    public SalonComboItem(UUID comboId, UUID serviceId, boolean requiresSpecialist, int sequence) {
        this.id = UuidV7.generate();
        this.comboId = comboId;
        this.serviceId = serviceId;
        this.requiresSpecialist = requiresSpecialist;
        this.sequence = sequence;

    }

    public UUID getId() { return id; }
    public UUID getComboId() { return comboId; }
    public UUID getServiceId() { return serviceId; }
    public boolean isRequiresSpecialist() { return requiresSpecialist; }
    public int getSequence() { return sequence; }
}
