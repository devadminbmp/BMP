package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * What a salon does: "hair", "skin", "nails", "spa". V011, Session 40.
 *
 * <h2>Why a child table rather than an array column</h2>
 * Postgres has {@code TEXT[]} and it would work. It needs Hibernate's array {@code JdbcType}
 * mapping — an exotic type in a codebase that currently has none — for a relation with at most
 * five rows per salon. A child table is boring, indexable, and joins the way every other
 * relation in this schema does.
 *
 * <p>A comma-separated string was the third option and is not really an option: it can't be
 * queried, and it eventually contains a comma.
 *
 * <h2>Why the category is free text</h2>
 * The taxonomy isn't settled. A {@code CHECK} constraint or a Java enum built on today's guess
 * would need a migration every time the business learns something, and the pressure would be to
 * shove the new thing into "other" instead. Normalised to lower case by the service, so "Hair"
 * and "hair" are one category rather than two.
 *
 * <p>The unique index on {@code (salon_id, category)} is what stops a salon listing "hair"
 * twice — cheaper and more reliable than a de-duplicating loop in the service.
 */
@Entity
@Table(name = "salon_category", schema = "salon_schema")
@Getter
public class SalonCategory {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;

    /** Lower-cased at the boundary. See {@code SalonService.replaceCategories}. */
    @Column(name = "category", nullable = false, length = 60)
    private String category;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SalonCategory() {} // JPA

    public SalonCategory(UUID salonId, String category) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.category = category;
        this.createdAt = Instant.now();
    }
}
