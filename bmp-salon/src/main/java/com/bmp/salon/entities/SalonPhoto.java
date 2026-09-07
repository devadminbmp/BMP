package com.bmp.salon.entities;

import com.bmp.common.ids.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A photo in a salon's gallery. V014 (Session 44).
 *
 * <p>Distinct from {@code Salon.imageUrl}, which is the single card thumbnail shown in search
 * results. This is the set a customer browses on the salon page before deciding to book — the
 * room, the chairs, work the team has done. One thumbnail identifies a salon; it does not sell it.
 *
 * <h2>url vs storageKey — reading vs owning</h2>
 * {@code url} is what a browser renders and is ALWAYS set. {@code storageKey} says who owns the
 * bytes behind it, and is the basis for every destructive decision:
 *
 * <ul>
 *   <li><b>set</b> — the owner uploaded this through BMP and we host it. Deleting the row must
 *       also delete the object, or storage fills with images nothing references.</li>
 *   <li><b>null</b> — the salon hosts it elsewhere and pasted a link (still fully supported).
 *       We must never issue a delete for it: it is not ours.</li>
 * </ul>
 *
 * <p>V015 added the key; before it, `url` was the only option and upload did not exist.
 */
@Entity
@Table(name = "salon_photo", schema = "salon_schema")
public class SalonPhoto {

    @Id
    private UUID id;

    @Column(name = "salon_id", nullable = false)
    private UUID salonId;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    /**
     * Object-storage key when WE host this image; null when the salon hosts it elsewhere.
     *
     * <p>Never derive this from {@code url}. A guessed key is a delete aimed at an object we may
     * not own, and the failure mode is silent. See the class javadoc.
     */
    @Column(name = "storage_key", length = 400)
    private String storageKey;

    /** Optional — a photo with no caption is still useful, and forcing one yields "photo 1". */
    @Column(name = "caption", length = 160)
    private String caption;

    /**
     * The owner's chosen order, not a timestamp sort. The newest photo is rarely the best one,
     * and the first photo is the one that sells the salon.
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SalonPhoto() {} // JPA

    /** A pasted link — the salon hosts the image, so there is no key. */
    public SalonPhoto(UUID salonId, String url, String caption, int sortOrder) {
        this(salonId, url, null, caption, sortOrder);
    }

    /** An upload — {@code storageKey} is what lets us delete the object later. */
    public SalonPhoto(UUID salonId, String url, String storageKey, String caption, int sortOrder) {
        this.id = UuidV7.generate();
        this.salonId = salonId;
        this.url = url;
        this.storageKey = storageKey;
        this.caption = caption;
        this.sortOrder = sortOrder;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSalonId() { return salonId; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public Instant getCreatedAt() { return createdAt; }
}
