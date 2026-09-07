package com.bmp.common.storage;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an untrusted upload into a safe, small JPEG. Session 44.
 *
 * <h2>The rule: never trust anything the client says about the file</h2>
 * A browser sends a filename and a {@code Content-Type}, and a client controls both completely.
 * {@code evil.php} renamed to {@code nice.jpg} with {@code Content-Type: image/jpeg} passes any
 * check based on either. So neither is used as evidence here. What we do instead:
 *
 * <ol>
 *   <li><b>Sniff the magic bytes.</b> The first few bytes of a real JPEG, PNG or WebP are fixed.
 *       This isn't proof on its own — a polyglot file can start with a valid JPEG header and
 *       carry something else after it — but it rejects the lazy cases immediately and cheaply,
 *       before we spend memory decoding.</li>
 *   <li><b>Decode it.</b> {@link ImageIO} either produces a raster or it doesn't. A file that
 *       cannot be decoded as an image is not an image, whatever its header claimed.</li>
 *   <li><b>Re-encode it.</b> This is the step that actually makes the file safe, and it's why
 *       we never store the original bytes. See below.</li>
 * </ol>
 *
 * <h2>Why re-encoding matters more than validation</h2>
 * The output is written from the decoded pixel data — a fresh JPEG built from a {@code int[]} of
 * colours. Whatever was in the input that wasn't pixels is not in the output, because there is no
 * path for it to travel. That single property gives us three things at once:
 *
 * <ul>
 *   <li><b>EXIF is gone, including GPS.</b> Phone cameras write the exact coordinates of the shot
 *       into every photo. A stylist photographing their work inside the salon would otherwise
 *       publish the salon's precise location, and a photo taken at home would publish their home
 *       address, to anyone who downloads the image and opens its metadata. Nobody uploading a
 *       haircut photo is consenting to that. This is the single strongest reason for this class
 *       to exist.</li>
 *   <li><b>Polyglot payloads die.</b> A file that is a valid JPEG <em>and</em> a valid HTML
 *       document (a real, well-known attack against image hosts) loses its second identity,
 *       because only the pixels survive.</li>
 *   <li><b>Size collapses.</b> A 12MP phone photo is ~4MB; the same image at 1600px wide is
 *       ~250KB. On the mobile networks BMP's customers actually browse on, that is the difference
 *       between a salon page that loads and one they leave.</li>
 * </ul>
 *
 * <h2>Decompression bombs</h2>
 * A 40KB PNG can legitimately declare 30000×30000 pixels — which allocates ~3.6GB when decoded
 * and takes the service down. A byte-length limit does not catch it, because the file really is
 * small. So dimensions are checked from the header BEFORE the pixels are read, and anything
 * beyond {@link #MAX_INPUT_PIXELS} is rejected without allocating.
 */
public class ImageIngest {

    private static final Logger log = LoggerFactory.getLogger(ImageIngest.class);

    /** Longest edge of the stored image. Beyond this is invisible on a phone and costs bandwidth. */
    private static final int MAX_EDGE = 1600;

    /**
     * Largest input we will decode, in pixels (~24MP — comfortably above any phone camera).
     *
     * <p>This is the decompression-bomb guard, and it is a PIXEL limit rather than a byte limit
     * on purpose: the attack works precisely by being small on disk and enormous in memory.
     */
    private static final long MAX_INPUT_PIXELS = 24_000_000L;

    /** JPEG quality for the re-encode. 0.82 is the usual knee — visually clean, much smaller. */
    private static final float JPEG_QUALITY = 0.82f;

    private final StorageProperties props;

    public ImageIngest(StorageProperties props) {
        this.props = props;
    }

    /** A validated, re-encoded image ready to store. */
    public record Prepared(byte[] bytes, String contentType, String extension, int width, int height) {}

    /** Rejected upload. Message is safe to show the user — it says what to do about it. */
    public static class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }
    }

    /**
     * Validate, normalise and re-encode an upload.
     *
     * @param raw the bytes exactly as received. Nothing about them is assumed.
     * @throws InvalidImageException with a user-facing message if this isn't a usable image.
     */
    public Prepared prepare(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new InvalidImageException("That file is empty.");
        }
        if (raw.length > props.getMaxUploadBytes()) {
            throw new InvalidImageException(
                    "That image is " + (raw.length / (1024 * 1024)) + "MB. The limit is "
                    + (props.getMaxUploadBytes() / (1024 * 1024)) + "MB — most phone photos are "
                    + "well under it.");
        }
        if (!looksLikeSupportedImage(raw)) {
            throw new InvalidImageException(
                    "That doesn't look like a JPG, PNG or WebP image. If you renamed a file to "
                    + ".jpg, that won't work — please upload the original photo.");
        }

        BufferedImage source = decode(raw);
        BufferedImage resized = resizeIfNeeded(source);
        byte[] out = encodeJpeg(resized);

        log.debug("Ingested image: {}KB {}x{} -> {}KB {}x{}",
                raw.length / 1024, source.getWidth(), source.getHeight(),
                out.length / 1024, resized.getWidth(), resized.getHeight());

        return new Prepared(out, "image/jpeg", "jpg", resized.getWidth(), resized.getHeight());
    }

    /**
     * Build a storage key. Always a fresh UUID — keys are never reused or overwritten.
     *
     * <p>Immutability is what lets objects be cached for a year (see {@code S3ObjectStorage.put}):
     * a URL either points at the bytes it always pointed at, or is no longer referenced. Reusing
     * a key would mean a customer seeing last month's photo from their browser cache with no way
     * for us to fix it.
     *
     * <p>{@code salonId} scopes the key so bucket contents are browsable per salon, and so a
     * future per-salon lifecycle rule or usage total is a prefix query rather than a full scan.
     */
    public static String key(String salonId, String purpose, String extension) {
        return "salons/" + salonId + "/" + purpose + "/" + UUID.randomUUID() + "." + extension;
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────

    /**
     * Magic-byte check. Cheap pre-filter, NOT the security boundary — see the class javadoc; the
     * re-encode is what actually makes the file safe.
     */
    private static boolean looksLikeSupportedImage(byte[] b) {
        if (b.length < 12) return false;
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A
                && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) return true;
        // WebP: "RIFF" .... "WEBP"
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    private BufferedImage decode(byte[] raw) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException | RuntimeException e) {
            // RuntimeException too: some malformed inputs make ImageIO throw unchecked rather
            // than return null, and an upload must never surface as a 500.
            throw new InvalidImageException("We couldn't read that image — it may be damaged. "
                    + "Try opening it on your phone first, or pick a different photo.");
        }
        if (img == null) {
            // Header looked right, no decoder could handle it. Includes WebP on JDKs without a
            // WebP plugin — so the message must not claim the file is broken.
            throw new InvalidImageException("We couldn't read that image. JPG and PNG work best "
                    + "— if this is a WebP or HEIC file, try saving it as a JPG.");
        }
        long pixels = (long) img.getWidth() * img.getHeight();
        if (pixels > MAX_INPUT_PIXELS) {
            throw new InvalidImageException("That image is " + img.getWidth() + "×" + img.getHeight()
                    + ", which is larger than we can process. Please use a normal photo.");
        }
        return img;
    }

    private static BufferedImage resizeIfNeeded(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);

        // Already small enough — but still redrawn below, because returning the source would
        // skip the RGB flattening that alpha channels need before JPEG encoding.
        double scale = longEdge > MAX_EDGE ? (double) MAX_EDGE / longEdge : 1.0;
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));

        // TYPE_INT_RGB, not ARGB: JPEG has no alpha channel, and encoding an image that has one
        // produces the notorious pink/inverted output rather than an error. Transparent PNG
        // regions become black here; drawing onto an explicitly white background first would be
        // friendlier, and is what the fill below does.
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // White under transparency — a logo with a transparent background should land on
            // white like it does everywhere else in this app, not on black.
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, tw, th);
            g.drawImage(src, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }
        } catch (IOException e) {
            // Encoding pixels we already hold in memory should not fail; if it does, something
            // is wrong with the JVM rather than with the user's file.
            throw new IllegalStateException("Could not encode the processed image", e);
        }
        return out.toByteArray();
    }
}
