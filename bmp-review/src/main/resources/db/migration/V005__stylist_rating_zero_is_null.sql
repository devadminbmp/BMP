-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V005 — "the customer didn't rate the stylist" is NULL, not 0. Session 48.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── THE BUG ────────────────────────────────────────────────────────────────────────────────────
-- V002 declared stylist_rating nullable with the comment "1-5, mandatory only if stylist
-- assigned". The entity mapped it to a Java primitive `int`, which cannot hold null, so
-- ReviewService.create compensated:
--
--     req.stylistRating() == null ? 0 : req.stylistRating()
--
-- Every review where the customer rated the salon but not the stylist was therefore stored as a
-- stylist rating of ZERO — a value outside the 1-5 range the column documents, written silently,
-- with no constraint to catch it.
--
-- ── WHY IT MATTERS NOW ─────────────────────────────────────────────────────────────────────────
-- Session 48 gives stylists a page showing what customers said about them. Those zeros would:
--   • render as a 0-star review the customer never left, attributed to a named person;
--   • pull their average down without limit — three unrated bookings and a 5.0 stylist reads 2.5.
--
-- The reason this was invisible until now is that nothing had ever read stylist_rating back for
-- display. The bug was written in the schema's first migration and has been dormant since.
--
-- ── THE FIX ────────────────────────────────────────────────────────────────────────────────────
-- 0 means "not rated", so 0 becomes NULL — which is what the column always meant. Then a CHECK
-- so it cannot happen again: from here the only accepted values are NULL or 1-5.
--
-- Safe to run on a database with no such rows; the UPDATE simply matches nothing.

UPDATE review_schema.review
SET stylist_rating = NULL
WHERE stylist_rating = 0;

UPDATE review_schema.review_edit_history
SET stylist_rating = NULL
WHERE stylist_rating = 0;

-- The guard V002 should have carried. Without it the next caller that defaults a missing rating
-- to zero reintroduces exactly this, and again nothing complains until somebody reads it.
ALTER TABLE review_schema.review
    ADD CONSTRAINT chk_review_stylist_rating
    CHECK (stylist_rating IS NULL OR stylist_rating BETWEEN 1 AND 5);

ALTER TABLE review_schema.review
    ADD CONSTRAINT chk_review_salon_rating
    CHECK (salon_rating BETWEEN 1 AND 5);

ALTER TABLE review_schema.review_edit_history
    ADD CONSTRAINT chk_edit_history_stylist_rating
    CHECK (stylist_rating IS NULL OR stylist_rating BETWEEN 1 AND 5);

COMMENT ON COLUMN review_schema.review.stylist_rating IS
    'NULL = the customer did not rate the stylist. 1-5 otherwise. NEVER 0 — see V005.';
