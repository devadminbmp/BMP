package com.bmp.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The four values that decide where images live. Session 44.
 *
 * <h2>This class IS the S3 migration</h2>
 * The promise made when MinIO was chosen was that moving to real object storage would be a config
 * change, not a rewrite. These properties are the whole surface of that promise:
 *
 * <pre>
 *   MinIO (local dev, today)          Cloudflare R2 (production, later)
 *   ───────────────────────           ────────────────────────────────
 *   endpoint: http://localhost:9000   endpoint: https://&lt;account&gt;.r2.cloudflarestorage.com
 *   bucket:   bmp-media               bucket:   bmp-media
 *   region:   us-east-1               region:   auto
 *   publicBaseUrl: http://localhost:9000/bmp-media
 *                                     publicBaseUrl: https://media.bemyprofessional.in
 * </pre>
 *
 * plus the two credentials, which come from the environment and never from a file in the repo.
 * No Java changes.
 *
 * <h2>Why publicBaseUrl is separate from endpoint</h2>
 * They look redundant in dev, where both are localhost:9000. They diverge the moment a CDN or a
 * custom domain exists: the service WRITES to the S3 endpoint over the internal network, while
 * browsers READ from the CDN domain. Collapsing them into one value works right up until the
 * first production deployment, then requires touching code — so they are two fields from the
 * start, and the dev config sets them to the same string.
 */
@ConfigurationProperties(prefix = "bmp.storage")
public class StorageProperties {

    /** S3 API endpoint the SERVICE writes to. MinIO in dev; the provider's endpoint in prod. */
    private String endpoint = "http://localhost:9000";

    /** Bucket name. Created on startup if absent — see {@link S3ObjectStorage}. */
    private String bucket = "bmp-media";

    /**
     * Region. MinIO ignores it, but the AWS SDK refuses to build a client without one, so this
     * is a required-but-meaningless value in dev. R2 uses {@code auto}.
     */
    private String region = "us-east-1";

    private String accessKey = "bmp-dev";
    private String secretKey = "devonly-minio-password";

    /**
     * Base URL browsers READ from. Object key is appended to it.
     *
     * <p>See the class javadoc for why this is not the same field as {@link #endpoint}.
     */
    private String publicBaseUrl = "http://localhost:9000/bmp-media";

    /**
     * Largest upload accepted, in bytes, BEFORE decoding.
     *
     * <p>Checked against the declared content length first and then against the actual bytes
     * read, because a client controls the header and can lie about it. 8MB is generous for a
     * phone photo (a 12MP JPEG is ~4MB) and small enough that a malicious upload can't exhaust
     * the heap — the decoded bitmap is what actually costs memory, and that's bounded separately
     * by the pixel cap in ImageIngest.
     */
    private long maxUploadBytes = 8L * 1024 * 1024;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }

    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
}
