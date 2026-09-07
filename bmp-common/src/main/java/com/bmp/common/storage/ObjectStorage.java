package com.bmp.common.storage;

/**
 * Somewhere to put bytes that is not the database.
 *
 * <h2>Why an interface at all</h2>
 * There is exactly one implementation today ({@link S3ObjectStorage}), and a one-implementation
 * interface is usually a smell. This one earns its place for a specific reason: the thing behind
 * it is expected to change. Session 44 ships against MinIO in Docker; pilot production will move
 * to real S3, Cloudflare R2 or DigitalOcean Spaces. All of those speak the S3 API, so the
 * implementation is not what changes — but the boundary is what makes that claim checkable, and
 * it keeps {@code SalonService} from ever mentioning a bucket.
 *
 * <h2>Keys, not URLs, are the identity</h2>
 * A stored object is identified by its <b>key</b> — {@code salons/{salonId}/gallery/{uuid}.jpg}.
 * The URL is derived from the key and the current public base, and is therefore disposable: move
 * providers, put a CDN in front, change domain, and every URL in the database would be wrong
 * while every key stays right.
 *
 * <p>We store BOTH ({@code url} for reading, {@code storage_key} for owning) which is a
 * deliberate, small denormalisation. The alternative — deriving the URL on every read — means
 * every response payload depends on config that could differ per environment, and a salon whose
 * photos 404 in staging but not production is a bad afternoon. The key is what lets us delete
 * the object later; the URL is what the app renders.
 *
 * <h2>What a null key means</h2>
 * A row with a URL and NO key is an image the salon hosts elsewhere — they pasted a link, which
 * remains fully supported. We must never try to delete those: it isn't ours, and the delete would
 * either fail or, worse, succeed against a key that coincidentally matched.
 */
public interface ObjectStorage {

    /**
     * Store bytes and return the public URL for reading them back.
     *
     * @param key         full object key, e.g. {@code salons/{id}/gallery/{uuid}.jpg}. Caller
     *                    generates it — the caller knows the tenancy, this class doesn't.
     * @param bytes       the content. Already validated and re-encoded by the caller; this
     *                    method does no inspection and MUST NOT be handed unvalidated upload
     *                    bytes.
     * @param contentType MIME type recorded on the object so browsers render rather than
     *                    download it.
     * @return the URL to store in the {@code url} column.
     */
    String put(String key, byte[] bytes, String contentType);

    /**
     * Remove an object.
     *
     * <p>Idempotent: deleting a key that isn't there is a success, not an error. Called during
     * cleanup after a database row has already gone, so the alternative is a failure path that
     * can never be retried into a good state.
     */
    void delete(String key);

    /** The public URL for a key, without storing anything. */
    String urlFor(String key);
}
