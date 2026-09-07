package com.bmp.common.storage;

import java.net.URI;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link ObjectStorage} over the S3 API — MinIO in dev, S3/R2/Spaces in production.
 *
 * <h2>Two settings that are not optional against MinIO</h2>
 *
 * <ul>
 *   <li><b>Path-style access.</b> The SDK defaults to virtual-host style, addressing a bucket as
 *       {@code https://bmp-media.localhost:9000/key}. That hostname does not resolve, and the
 *       failure surfaces as a DNS or connection error that says nothing about buckets — an
 *       genuinely confusing hour if you don't know to look for it. Path style
 *       ({@code http://localhost:9000/bmp-media/key}) is what MinIO serves. Real S3 accepts both,
 *       so this stays correct after the migration.</li>
 *   <li><b>Static credentials.</b> Without them the SDK walks its default provider chain — env
 *       vars, profile files, then a 1-second-timeout HTTP call to the EC2 instance metadata
 *       endpoint. On a developer laptop that last step is a hang followed by an error naming a
 *       service nobody is using.</li>
 * </ul>
 *
 * <h2>The bucket is created on startup</h2>
 * A fresh {@code docker compose up} gives you an empty MinIO, and the first upload would fail
 * with {@code NoSuchBucket} — a real error that reads like a bug in BMP. Creating it at startup
 * means a new developer's first upload works, which is worth the small amount of code. Against
 * real S3 the bucket exists already and {@link #ensureBucket()} finds it on the first HEAD.
 *
 * <p>Deliberately NOT fatal if it fails. A service that refuses to start because object storage
 * is unreachable takes down booking, availability and the whole salon desk over a feature used a
 * few times a week. Uploads fail loudly at the point of use instead; everything else keeps
 * working. <b>The blast radius of a dependency should match its importance.</b>
 *
 * <h2>@ConditionalOnClass</h2>
 * bmp-common declares the SDK {@code optional}, so on a service that didn't opt in this class is
 * simply not a candidate for instantiation. See the comment in bmp-common/pom.xml.
 */
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client client;
    private final StorageProperties props;

    public S3ObjectStorage(StorageProperties props) {
        this.props = props;
        this.client = S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                // See class javadoc — non-negotiable against MinIO, harmless against S3.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /**
     * Create the bucket if it isn't there. Logs and continues on failure — see class javadoc for
     * why this must not stop the service from starting.
     */
    public void ensureBucket() {
        String bucket = props.getBucket();
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Object storage ready: bucket '{}' at {}", bucket, props.getEndpoint());
        } catch (NoSuchBucketException e) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Object storage: created missing bucket '{}' at {}", bucket, props.getEndpoint());
            } catch (BucketAlreadyOwnedByYouException race) {
                // Two service instances starting together. Both wanted it to exist; it does.
                log.debug("Bucket '{}' created concurrently by another instance", bucket);
            } catch (S3Exception create) {
                log.error("Object storage: could not create bucket '{}' at {} — IMAGE UPLOADS WILL "
                        + "FAIL until this is fixed. Everything else keeps working. Cause: {}",
                        bucket, props.getEndpoint(), create.getMessage());
            }
        } catch (Exception e) {
            // Typically MinIO not running at all. Same reasoning: loud, but not fatal.
            log.error("Object storage at {} is unreachable — IMAGE UPLOADS WILL FAIL until this is "
                    + "fixed. Is MinIO running? `docker compose up -d minio`. Cause: {}",
                    props.getEndpoint(), e.getMessage());
        }
    }

    @Override
    public String put(String key, byte[] bytes, String contentType) {
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(key)
                            .contentType(contentType)
                            // Long cache lifetime is safe because keys are immutable: every
                            // upload gets a fresh UUID and edits write a NEW key rather than
                            // overwriting. So a cached image can never be stale — the URL that
                            // pointed at it is simply no longer referenced.
                            .cacheControl("public, max-age=" + Duration.ofDays(365).toSeconds() + ", immutable")
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (Exception e) {
            throw new StorageException(
                    "Could not store image at key '" + key + "'. Is object storage reachable at "
                    + props.getEndpoint() + "?", e);
        }
        return urlFor(key);
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
        } catch (Exception e) {
            // Swallowed on purpose. Callers delete the DATABASE ROW first, then the object; by
            // the time we get here the user's action has already succeeded and there is nothing
            // useful to tell them. A leaked object costs a fraction of a rupee and is findable
            // later by diffing bucket keys against storage_key values. Failing the request would
            // report "delete failed" for a photo that is, in fact, gone.
            log.warn("Could not delete object '{}' — the row is gone but the file remains. "
                    + "Orphan, not corruption. Cause: {}", key, e.getMessage());
        }
    }

    @Override
    public String urlFor(String key) {
        String base = props.getPublicBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + key;
    }

    /** Thrown when an upload genuinely fails, so the caller can turn it into a 503. */
    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
