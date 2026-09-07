-- V015__uploaded_image_storage_keys.sql  (Session 44)
--
-- ═════════════════════════════════════════════════════════════════════════════════════════════
-- REAL FILE UPLOAD. This closes the limitation V013 and V014 each apologised for in their own
-- headers, and that PENDING_WORK.md listed as "the same problem in three places".
-- ═════════════════════════════════════════════════════════════════════════════════════════════
--
-- Salon owners do not have a CDN. They have a phone with photos on it. Asking them to "paste a
-- link to an image you host elsewhere" was an honest description of what the system could do and
-- a bad answer to what they needed. There is now object storage (MinIO in dev, S3-compatible so
-- the production move is config only — see bmp-salon's application.yml), and images can be
-- uploaded directly as JPG/PNG/WebP.
--
-- ---------------------------------------------------------------------------------------------
-- WHY NO COLUMN CHANGES SHAPE, AND WHY THIS MIGRATION IS SO SMALL
-- ---------------------------------------------------------------------------------------------
-- The obvious design is a `source` enum ('UPLOADED' | 'EXTERNAL') plus separate columns. That
-- would ripple into every read path, every DTO and every frontend schema, to express something
-- nothing downstream actually needs to know: a customer's browser renders a URL, and where the
-- bytes live is not its business.
--
-- So `url` stays exactly what it was — the single source of truth for READING — and everything
-- that consumes an image keeps working with no change at all. Pasting a URL is still fully
-- supported and still writes only `url`.
--
-- What we add is one nullable column recording OWNERSHIP:
--
--     storage_key NOT NULL  ->  we uploaded this; the object is ours; delete it when the row goes
--     storage_key NULL      ->  the salon hosts it elsewhere; NEVER touch it
--
-- That distinction is not cosmetic. Without it, deleting a photo row either leaks the object
-- forever (storage bill grows, nothing ever cleans it) or, if we guessed a key from the URL,
-- risks issuing deletes against keys we don't own. Explicit ownership is the only safe basis for
-- a destructive operation.
--
-- ---------------------------------------------------------------------------------------------
-- KEYS ARE NOT URLs, AND THE DIFFERENCE MATTERS LATER
-- ---------------------------------------------------------------------------------------------
-- A key looks like `salons/{salonId}/gallery/{uuid}.jpg` — it has no host, no scheme and no
-- bucket in it. Put a CDN in front, move to Cloudflare R2, change domain: every stored URL would
-- be wrong and every stored key would still be right. Keeping the key means such a move is one
-- UPDATE that rewrites urls from keys, rather than an archaeology exercise.
--
-- 400 chars: a key is `salons/` + a 36-char UUID + `/gallery/` + a 36-char UUID + `.jpg` ≈ 95.
-- The headroom is for future prefixes, not for anything to grow into.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE salon_schema.salon_photo
    ADD COLUMN storage_key VARCHAR(400);

ALTER TABLE salon_schema.salon_service
    ADD COLUMN image_storage_key VARCHAR(400);

ALTER TABLE salon_schema.salon
    ADD COLUMN image_storage_key VARCHAR(400);

COMMENT ON COLUMN salon_schema.salon_photo.storage_key IS
    'Object-storage key when WE host this image, NULL when the salon hosts it elsewhere. The '
    'flag that says whether deleting this row should also delete a file. Never derive this from '
    'url — an unowned key is a delete against someone else''s object.';

COMMENT ON COLUMN salon_schema.salon_service.image_storage_key IS
    'Object-storage key for an uploaded service photo; NULL when image_url points somewhere the '
    'salon hosts. Replacing the photo must delete the OLD key, or every re-upload leaks an object.';

COMMENT ON COLUMN salon_schema.salon.image_storage_key IS
    'Object-storage key for an uploaded salon cover image; NULL when image_url is external.';

-- ---------------------------------------------------------------------------------------------
-- Finding orphans.
-- ---------------------------------------------------------------------------------------------
-- Object deletion happens AFTER the row is gone and is deliberately non-fatal (see
-- S3ObjectStorage.delete — failing the request would report "delete failed" for a photo that is
-- in fact deleted). So a storage outage during a delete leaks an object, by design.
--
-- These indexes make the reconciliation cheap: list the bucket, subtract the keys below, and
-- what remains is garbage. Partial, because the overwhelming majority of rows will have NULL
-- here and indexing NULLs would be pure overhead.
CREATE INDEX idx_salon_photo_storage_key
    ON salon_schema.salon_photo (storage_key) WHERE storage_key IS NOT NULL;

CREATE INDEX idx_salon_service_storage_key
    ON salon_schema.salon_service (image_storage_key) WHERE image_storage_key IS NOT NULL;

CREATE INDEX idx_salon_storage_key
    ON salon_schema.salon (image_storage_key) WHERE image_storage_key IS NOT NULL;
