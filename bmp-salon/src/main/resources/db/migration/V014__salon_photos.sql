-- V014__salon_photos.sql  (Session 44)
--
-- A salon has ONE image_url today (V011) — the card thumbnail. That is enough to identify a
-- salon in a list and nowhere near enough to choose one. Customers pick a salon by looking at
-- it: the room, the chairs, work the team has done. One photo cannot carry that.
--
-- A child table rather than more columns on `salon`:
--   · the count is genuinely unbounded — image_url_2..image_url_6 would be a column every time
--     someone wants one more, and five NULLs for the salon that uploaded one;
--   · ordering is data, not schema. The owner decides which photo leads, and that has to be
--     editable without a migration;
--   · a caption belongs with its photo, not in a parallel array.
--
-- ---------------------------------------------------------------------------------------------
-- STILL URLs, STILL NOT UPLOADS
-- ---------------------------------------------------------------------------------------------
-- There is no object storage in this system — see V013's header. `url` is a link to an image the
-- salon hosts elsewhere. That limitation is now in three places (salon.image_url,
-- salon_service.image_url, here), which is itself the argument for doing the storage work
-- properly rather than adding a fourth. Tracked in docs/PENDING_WORK.md.
--
-- The API validates http/https on the way in. On the web build these strings land in an image
-- source, so a permissive column is stored XSS aimed at customers — filed by the salon itself.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE salon_schema.salon_photo (
    id          UUID PRIMARY KEY NOT NULL,           -- UUIDv7
    salon_id    UUID NOT NULL,
    url         VARCHAR(500) NOT NULL,
    -- "Our colour bar", "After a bridal set". Optional: a photo with no caption is still useful,
    -- and forcing one produces "photo 1", "photo 2".
    caption     VARCHAR(160),
    -- The owner's chosen order. Not a timestamp sort: the newest photo is rarely the best one,
    -- and the first photo is the one that sells the salon.
    sort_order  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL
);

ALTER TABLE salon_schema.salon_photo
    ADD CONSTRAINT fk_salon_photo_salon_id
    FOREIGN KEY (salon_id) REFERENCES salon_schema.salon(id) ON DELETE CASCADE;

-- ON DELETE CASCADE here, unlike everywhere else in this schema, and deliberately: a photo has
-- no independent meaning. Nothing references it, no booking snapshots it, and a gallery row for
-- a salon that no longer exists is litter rather than history. Contrast salon_service, where a
-- delete would orphan real records — which is why that one archives instead (V012).

-- The gallery is read on every salon page load, always for one salon, always in display order.
CREATE INDEX idx_salon_photo_salon ON salon_schema.salon_photo (salon_id, sort_order);

COMMENT ON TABLE salon_schema.salon_photo IS
    'The salon gallery a customer browses before booking. URLs to externally hosted images — '
    'there is no upload endpoint (see V013/V014 headers). Distinct from salon.image_url, which '
    'is the single card thumbnail used in search results.';
