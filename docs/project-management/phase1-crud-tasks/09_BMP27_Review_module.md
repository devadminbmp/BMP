# BMP-27 — CRUD: Review module (bmp-review)

**Order in Phase 1:** 9 of 9
**Difficulty:** Medium
**Type:** Feature   **Priority:** Medium
**Suggested owner:** Darshan
**Target date:** 2026-07-19
**Depends on:** BMP-25 (needs a real booking_id)

## One-line summary

Migration V007 (review half) + CRUD for review and salon_response. Rating snapshots (computed aggregates) are read-only, not user-editable.

---

## Full Task Detail

### Where this lives

- **Service:** bmp-review   SCHEMA: review_schema   MIGRATION: V007__review_and_rewards_schema.sql (shared file per V001's comment grouping — coordinate with BMP-28 so both land in one migration, or split into V007a/V007b if the team prefers two files; either way use the SAME next free number space, don't collide with V006/V008)
- **Files:** bmp-review/src/main/java/com/bmp/review/internal/Review.java, SalonResponse.java, ReviewController.java

COLUMNS (from CONTEXT.md Module 5, core Postgres tables — MongoDB community_posts bridge is OUT of scope for this ticket, that's a Phase 2/3 addition once the community feed is being built):
  review: id UUID PK, booking_id UUID UNIQUE NOT NULL (logical FK to booking_schema.booking — "no booking, no review"), salon_id, stylist_id NULL, salon_rating SMALLINT (1-5, mandatory), stylist_rating SMALLINT NULL (1-5, mandatory only if a stylist was assigned), text TEXT NULL, edit_locked_at TIMESTAMPTZ (= created_at + 7 days), needs_remoderation BOOLEAN DEFAULT false, community_post_id VARCHAR(36) NULL (Mongo bridge, leave null for now), created_at, updated_at.
  salon_response: id UUID PK, review_id FK, salon_id, text TEXT, created_at, updated_at (editable within 24h per locked rule — enforce in service layer for this ticket, don't need a full moderation queue yet).

### Endpoints

#### Endpoint 1: `POST /api/v1/bookings/{bookingId}/review`

   Request: { "salonRating":5, "stylistRating":5, "text":"Great haircut, on time!" }
   Response 201: { "id":"uuid","bookingId":"uuid","salonRating":5,"stylistRating":5,"text":"...","editLockedAt":"2026-07-26T10:00:00Z","createdAt":"..." }
   Validation: 400 if booking status is not COMPLETED (a review requires a completed, paid booking — enforce even though full booking completion logic isn't wired until later, just check booking.status='COMPLETED' if present). 409 if a review already exists for this booking_id (UNIQUE constraint).

#### Endpoint 2: `GET /api/v1/reviews/{reviewId}`

   Response 200: same shape as above.

#### Endpoint 3: `GET /api/v1/salons/{salonId}/reviews?page=0&size=20`

   Response 200: paginated list of reviews for a salon.

#### Endpoint 4: `PUT /api/v1/reviews/{reviewId}`

(edit — only within the 7-day window)
   Request: { "salonRating":4, "text":"Updating my review, one issue on revisit" }
   Response 200: updated object.
   Response 403 if now() > edit_locked_at: { "error":"REVIEW_EDIT_WINDOW_CLOSED" }
   Sets needs_remoderation=true if text changed (per locked rule) — this ticket just needs to set the flag; an actual moderation queue/workflow is a later ticket.

#### Endpoint 5: `POST /api/v1/reviews/{reviewId}/response`

and   PUT /api/v1/reviews/{reviewId}/response
   Request: { "text":"Thanks for the 5 stars!" }
   Response 201/200: { "id":"uuid","reviewId":"uuid","text":"...","createdAt":"...","updatedAt":"..." }
   PUT should 403 if now() > response.created_at + 24h.

### Data Types

salonRating/stylistRating = integer 1-5 (validate range, 400 outside it). editLockedAt = ISO-8601 timestamp.

### Acceptance Criteria

  - Attempting a second review on the same booking_id returns 409, not a duplicate row.
  - Editing a review after edit_locked_at returns 403, verified with a test that manually backdates created_at.
  - Editing review text (not just rating) sets needs_remoderation=true; editing ONLY the rating does not (matches the locked distinction in CONTEXT.md).

### Depends On

BMP-25 (needs a real booking_id, ideally one that can be marked COMPLETED for testing).

### Blocks

salon_rating_snapshot/stylist_rating_snapshot computation (a follow-up ticket — these are pre-computed aggregates updated after each approved review, not built in this CRUD pass).

---

*This file is self-contained — hand it directly to whoever is building this task.
Update the status on the team tracker spreadsheet (Tasks sheet) when done.*
