package com.bmp.admin.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A runtime platform setting (admin_schema.platform_setting, V003).
 *
 * <p>Exists so ops can turn something off at 2am without a deploy — most importantly
 * {@code new_bookings_enabled}, the kill switch that stops customers making new bookings
 * platform-wide during an incident, without touching bookings that already exist.
 *
 * <p>Every change is audited with the actor and their reason. A feature flag nobody can
 * attribute is how a platform ends up in a state nobody admits to causing.
 */
@Entity
@Table(name = "platform_setting", schema = "admin_schema")
public class PlatformSetting {

    @Id
    private UUID id;

    @Column(name = "setting_key", nullable = false, length = 80)
    private String settingKey;

    @Column(name = "setting_value", nullable = false)
    private String settingValue;

    /** boolean | number | string | json — lets the console render the right control. */
    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;

    @Column(name = "description")
    private String description;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlatformSetting() {} // JPA

    public PlatformSetting(String settingKey, String settingValue, String valueType, String description) {
        this.id = UuidV7.generate();
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.valueType = valueType;
        this.description = description;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void update(String value, UUID by) {
        this.settingValue = value;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public boolean asBoolean() {
        return Boolean.parseBoolean(settingValue);
    }

    /** Falls back rather than throwing — a corrupted row must not take a service down. */
    public long asLong(long fallback) {
        try {
            return Long.parseLong(settingValue);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public UUID getId() { return id; }
    public String getSettingKey() { return settingKey; }
    public String getSettingValue() { return settingValue; }
    public String getValueType() { return valueType; }
    public String getDescription() { return description; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
