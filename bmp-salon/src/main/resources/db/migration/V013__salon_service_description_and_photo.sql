-- V013__salon_service_description_and_photo.sql  (Session 44)
--
-- The salon's menu currently carries name, price, duration and category — the facts a booking
-- needs. It carries nothing that helps a customer DECIDE. "Global hair colour · ₹3,500 · 120 min"
-- tells you the cost of a thing you may not be able to picture, and the salon has no way to say
-- what's included, what to expect, or what it looks like.
--
-- Two columns, mirroring what `salon` already has (`about`, `image_url`) so the menu and the
-- salon page describe themselves the same way.
--
--   description  what's included, what to expect. Longer than a name, shorter than an essay.
--   image_url    a photo of the result.
--
-- ---------------------------------------------------------------------------------------------
-- image_url IS A URL, NOT AN UPLOAD, AND THAT IS A LIMITATION NOT A DESIGN
-- ---------------------------------------------------------------------------------------------
-- There is NO file-upload infrastructure anywhere in this backend — no object storage, no
-- presigned URLs, no MultipartFile handler, nothing. `salon.image_url` has the same shape for the
-- same reason. So an owner pastes a link to an image they host elsewhere (their Instagram, Google
-- Business, a Drive share).
--
-- That is genuinely useful — most salons already have photos somewhere — but it is not "upload a
-- photo", and the UI must not imply it is. Real upload needs object storage, presigned PUTs,
-- size/MIME validation and a CDN; it is infrastructure work, not a form field, and it is tracked
-- in docs/PENDING_WORK.md rather than half-built here.
--
-- 500 chars matches salon.image_url. Long enough for a signed CDN URL, short enough that nobody
-- pastes a base64 image into it.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE salon_schema.salon_service
    ADD COLUMN description VARCHAR(600),
    ADD COLUMN image_url   VARCHAR(500);

COMMENT ON COLUMN salon_schema.salon_service.description IS
    'What the service includes and what to expect. Shown on the salon page under the service '
    'name. NULL means the name has to speak for itself.';

COMMENT ON COLUMN salon_schema.salon_service.image_url IS
    'URL of a photo, hosted elsewhere — there is no upload endpoint (see V013 header). NULL is '
    'normal and the UI degrades to a tinted block rather than a broken-image icon.';
