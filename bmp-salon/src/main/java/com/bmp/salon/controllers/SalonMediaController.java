package com.bmp.salon.controllers;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.bmp.common.storage.ImageIngest;
import com.bmp.common.storage.ObjectStorage;
import com.bmp.common.storage.S3ObjectStorage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Image upload. Session 44 — the endpoint three migrations said didn't exist.
 *
 * <h2>Uploads go THROUGH this service, not straight to the bucket</h2>
 * The fashionable design is a presigned URL: the server hands the client a signed link and the
 * client PUTs the file directly to storage. It scales better and it is the wrong choice here,
 * because <b>a presigned PUT cannot be validated</b>. Whatever the client sends is what lands in
 * the bucket — a 400MB file, an HTML document named {@code .jpg}, a photo carrying the GPS
 * coordinates of someone's home. The server never sees the bytes and so can never object.
 *
 * <p>Routing the bytes through here costs bandwidth we can easily afford at this size (capped at
 * 8MB, twelve photos per salon) and buys the ability to reject, re-encode and strip. Given that
 * these images are published publicly on a salon's page, that trade is not close. Revisit it when
 * upload volume is a real cost — the interface won't change.
 *
 * <h2>Two steps, and why the second one re-checks the first</h2>
 * Upload returns a {@code url} and a {@code storageKey}; the caller then passes both to the
 * ordinary create/update endpoint (add a photo, set a service image). Two calls rather than one
 * combined endpoint, because it lets one upload path serve all three image surfaces instead of
 * three multipart handlers that drift apart.
 *
 * <p>The cost of that split is that a client could upload to its own salon and then present the
 * resulting key when writing to a DIFFERENT salon's row — a classic <b>authorise the path, then
 * trust the body</b> hole, which is the recurring shape in this codebase. The key embeds the
 * salon id ({@code salons/{salonId}/...}), so {@code SalonService} re-checks that the key it was
 * handed belongs to the salon being written. Authorising the upload is not the same as
 * authorising the use.
 */
@RestController
@RequestMapping("/api/v1/salons")
@Tag(name = "Salon media", description = "Image upload for salon galleries, service photos and cover images")
public class SalonMediaController {

    private final ObjectStorage storage;
    private final ImageIngest ingest;

    public SalonMediaController(ObjectStorage storage, ImageIngest ingest) {
        this.storage = storage;
        this.ingest = ingest;
    }

    /**
     * What the caller gets back. {@code url} is for rendering; {@code storageKey} is what proves
     * to the follow-up call that this image is ours to keep and later delete.
     */
    public record UploadedImage(String url, String storageKey, int width, int height) {}

    /**
     * Accept an image, validate and re-encode it, store it, and return where it went.
     *
     * <p>OWNER or MANAGER of THIS salon. Same rule as the photo and service endpoints — arranging
     * the shop window is day-to-day work, not a decision about the business's identity. The
     * {@code principal.salonId().equals(#salonId)} clause is what stops an owner of one salon
     * writing into another; role says what KIND of user, salonId says WHICH one.
     *
     * @param purpose where this image is destined — {@code gallery}, {@code service} or
     *                {@code cover}. Only used to organise keys, so a bucket listing is readable
     *                and a future per-purpose lifecycle rule is a prefix query.
     */
    @Operation(summary = "Upload an image",
            description = "Accepts JPG, PNG or WebP up to 8MB. The image is re-encoded server-side, "
                    + "which strips EXIF metadata (including GPS location) and resizes it for the "
                    + "web. Returns the URL to display and the storage key to pass to the "
                    + "create/update call that will reference it.")
    @PreAuthorize("hasAnyRole('SALON_OWNER','MANAGER') and principal.salonId() != null and principal.salonId().equals(#salonId)")
    @PostMapping(value = "/{salonId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadedImage> upload(
            @PathVariable String salonId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "purpose", defaultValue = "gallery") String purpose) {

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was attached.");
        }

        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            // The upload was cut off mid-stream. The client's connection failed, not our logic —
            // 400 rather than 500, because there is nothing broken here to fix.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That upload didn't finish. Check your connection and try again.");
        }

        ImageIngest.Prepared prepared;
        try {
            prepared = ingest.prepare(raw);
        } catch (ImageIngest.InvalidImageException e) {
            // These messages are written to be read by a salon owner and say what to do next —
            // see ImageIngest. Passed through verbatim rather than replaced with "Bad Request".
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        String key = ImageIngest.key(salonId, safePurpose(purpose), prepared.extension());
        String url;
        try {
            url = storage.put(key, prepared.bytes(), prepared.contentType());
        } catch (S3ObjectStorage.StorageException e) {
            // 503, not 500: the request was fine and retrying later is the correct advice.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "We couldn't save that image just now. Please try again in a moment.");
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UploadedImage(url, key, prepared.width(), prepared.height()));
    }

    /**
     * Constrain {@code purpose} to a known set.
     *
     * <p>This value becomes part of an object key, so an unchecked one is path injection into the
     * bucket: {@code ../../} or a leading slash would place objects outside the salon's prefix,
     * defeating the ownership check that {@code SalonService} performs on the key. An allowlist
     * is the only safe way to build a path segment out of user input.
     */
    private static String safePurpose(String purpose) {
        return switch (purpose == null ? "" : purpose.toLowerCase()) {
            case "service" -> "service";
            case "cover" -> "cover";
            default -> "gallery";
        };
    }
}
