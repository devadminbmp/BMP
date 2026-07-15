package com.bmp.payment.internal.entity;

import com.bmp.common.ids.UuidV7;
import com.bmp.common.money.Money;
import com.bmp.common.money.MoneyAttributeConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for payment_schema.saas_subscription.
 * Generated from the locked column definitions in CONTEXT.md.
 * created_at/updated_at are set automatically at construction time (matching
 * the convention already used by com.bmp.common.outbox.OutboxEntry in this repo).
 * Getters only where a field is documented FROZEN/append-only in CONTEXT.md;
 * plain getters otherwise — add bespoke mutation methods per table as real
 * invariants surface (fast-moving pre-PMF team, not a final API).
 */
@Entity
@Table(name = "saas_subscription", schema = "payment_schema")
public class SaasSubscription {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;
    @Column(name = "plan", nullable = false, length = 10)
    private String plan;
    @Convert(converter = MoneyAttributeConverter.class)
    @Column(name = "monthly_fee_paise", nullable = false)
    private Money monthlyFeePaise;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    protected SaasSubscription() {} // JPA

    public SaasSubscription(UUID salonId, String plan, Money monthlyFeePaise, Instant startedAt, String status) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.plan = plan;
        this.monthlyFeePaise = monthlyFeePaise;
        this.startedAt = startedAt;
        this.status = status;

    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }
    public String getPlan() { return plan; }
    public Money getMonthlyFeePaise() { return monthlyFeePaise; }
    public Instant getStartedAt() { return startedAt; }
    public String getStatus() { return status; }
}
