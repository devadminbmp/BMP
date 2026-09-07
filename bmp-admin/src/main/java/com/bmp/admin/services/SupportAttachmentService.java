package com.bmp.admin.services;

import com.bmp.admin.entities.SupportAttachment;
import com.bmp.admin.repositories.SupportAttachmentRepository;
import com.bmp.common.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Accepting a photo or document into a support conversation. Session 57.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * AN UPLOAD ENDPOINT IS THE MOST ATTACKED THING IN A PRODUCT
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * This one is reachable by any logged-in customer, which is the widest audience of any write in
 * the platform. Four rules, each closing something specific:
 *
 * <ol>
 *   <li><b>Type is DETECTED, never believed.</b> The client's {@code Content-Type} is a string it
 *       chose. Magic bytes decide, following {@code ImageIngest} (Session 42). Otherwise somebody
 *       stores {@code invoice.pdf.exe} labelled {@code image/jpeg} and a support agent's browser
 *       is invited to open it.</li>
 *   <li><b>Size is capped before anything is read into memory</b> — 25 MB, matched by a database
 *       CHECK so the two cannot drift.</li>
 *   <li><b>The key is generated here.</b> A client-supplied path is a directory traversal and an
 *       overwrite of somebody else's object.</li>
 *   <li><b>Private objects.</b> A storage key, not a URL — support threads contain faces,
 *       receipts and screenshots of bank statements. See {@link SupportAttachment}.</li>
 * </ol>
 */
@Service
public class SupportAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(SupportAttachmentService.class);

    /** Matches chk_attachment_size in V009. Both exist so neither can drift unnoticed. */
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    /**
     * What a support conversation legitimately needs to carry.
     *
     * <p>An ALLOW-list, not a block-list. A block-list is a list of the file types somebody
     * thought of, and the interesting ones are always the ones they did not.
     *
     * <p>PDF is here because refund disputes involve bank statements. Office documents are not:
     * they carry macros, and nothing in a salon complaint needs a spreadsheet.
     */
    private static final Set<String> ALLOWED = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/heic", "application/pdf");

    private final SupportAttachmentRepository attachments;
    private final ObjectStorage storage;

    public SupportAttachmentService(SupportAttachmentRepository attachments, ObjectStorage storage) {
        this.attachments = attachments;
        this.storage = storage;
    }

    /**
     * Store one file against a message that already exists.
     *
     * <p>Message first, attachment second, deliberately: an attachment with no message is
     * unreachable in the UI, whereas a message whose attachment failed still says something and
     * the sender can retry the file.
     */
    @Transactional
    public SupportAttachment attach(UUID ticketId, UUID messageId, MultipartFile file,
                                     String uploaderType, UUID uploaderId) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That file is larger than 25 MB. Try a photo instead of a video.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read that file.");
        }

        String detected = sniff(bytes);
        if (detected == null || !ALLOWED.contains(detected)) {
            /*
             * Note what is logged and what is not: the DETECTED type, never the file's contents or
             * its name. A rejected upload is often the interesting one, and the name may itself be
             * the attack.
             */
            log.warn("Rejected an attachment on ticket {} — detected type {} is not allowed.",
                    ticketId, detected);
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Send a photo (JPG, PNG, WEBP, HEIC) or a PDF.");
        }

        // Generated here, never from the client. Ticket-scoped so a leaked key reveals nothing
        // about any other conversation.
        String key = "support/" + ticketId + "/" + UUID.randomUUID() + extensionFor(detected);
        storage.put(key, bytes, detected);

        SupportAttachment saved = attachments.save(new SupportAttachment(
                messageId, ticketId, key, safeName(file.getOriginalFilename()),
                detected, bytes.length, uploaderType, uploaderId));

        log.info("Attachment {} ({}, {} bytes) added to ticket {} by {}.",
                saved.getId(), detected, bytes.length, ticketId, uploaderType);
        return saved;
    }

    /**
     * Identify a file by its leading bytes.
     *
     * <p>Deliberately small and readable rather than a library: five formats, each a documented
     * fixed signature. The point is not exhaustive detection — it is that the ANSWER DOES NOT COME
     * FROM THE CLIENT. Anything not recognised is refused, so an unknown format fails closed.
     */
    private static String sniff(byte[] b) {
        if (b.length < 12) return null;

        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A) {
            return "image/png";
        }
        // PDF: %PDF
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return "application/pdf";
        }
        // RIFF....WEBP — the format marker is at offset 8, not at the start.
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        // HEIC: an ISO-BMFF box whose type is 'ftyp' at offset 4. Common on iPhones, so refusing
        // it would reject the most likely photo a customer sends.
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return "image/heic";
        }
        return null;
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/heic" -> ".heic";
            case "application/pdf" -> ".pdf";
            default -> "";
        };
    }

    /**
     * A display name that cannot be a path, a script, or a novel.
     *
     * <p>It is only ever rendered as text next to a download chip, but "only ever" is the phrase
     * that precedes most stored-XSS reports. Path separators go because a name containing one
     * reads as a directory in a UI and invites somebody to treat it as a location.
     */
    private static String safeName(String original) {
        if (original == null || original.isBlank()) return null;
        String cleaned = original.replaceAll("[\\\\/\\r\\n\\t<>\"']", "").trim();
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    public List<SupportAttachment> forMessage(UUID messageId) {
        return attachments.findByMessageIdOrderByCreatedAtAsc(messageId);
    }

    public List<SupportAttachment> forTicket(UUID ticketId) {
        return attachments.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }
}
