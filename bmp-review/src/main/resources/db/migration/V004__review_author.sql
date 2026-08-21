-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- V004 — a review has an author. Session 40.
-- ═══════════════════════════════════════════════════════════════════════════════════════════
--
-- WHAT WAS FOUND
-- `PUT /api/v1/reviews/{reviewId}` had no @PreAuthorize AND is matched by this service's
-- public-paths entry `/api/v1/reviews/*`. So it was reachable **with no credential at all**:
-- anyone on the internet could rewrite any review on the platform, within its 24h edit window.
--
-- The public-paths entry was written for the GET — a review is public to read, correctly — and
-- it silently opened the PUT on the same path, because **public-paths are path-only and
-- method-blind**. `/api/v1/reviews/*` does not say "GET"; it says "this URL".
--
-- Session 29 recorded the lesson as "a path is not a permission" after finding
-- POST /admin/wallet/credit open. This is the same lesson from the other direction: a path that
-- SHOULD be open for one verb is open for all of them.
--
-- WHY A COLUMN AND NOT JUST AN ANNOTATION
-- `@PreAuthorize("hasRole('CUSTOMER')")` fixes "anyone" and leaves "any customer". Without an
-- author on the row there is nothing to compare the caller against — this table stored
-- booking_id, salon_id, ratings and text, and no notion of who wrote it. So one customer could
-- still edit another customer's review, which is the version of the bug that survives review
-- because the endpoint now *looks* protected.
--
-- Deriving it through bmp-booking (booking_id -> customer_id) was the alternative: a
-- cross-service call on every edit, to learn a fact that can never change. Storing it is both
-- cheaper and more honest — authorship IS a property of the review.
--
-- NULLABLE, no backfill. Rows written before this migration have no author, and the service
-- refuses to edit them rather than guessing (see ReviewService.update). There are no real
-- reviews yet; if there ever are, a one-off backfill from booking_id is the fix, not a default.
-- ═══════════════════════════════════════════════════════════════════════════════════════════

ALTER TABLE review_schema.review ADD COLUMN IF NOT EXISTS author_user_id UUID;

-- "Has this customer reviewed before", and the ownership check on edit.
CREATE INDEX IF NOT EXISTS idx_review_author ON review_schema.review(author_user_id);
