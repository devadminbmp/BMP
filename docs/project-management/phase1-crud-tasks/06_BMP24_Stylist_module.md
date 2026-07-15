# BMP-24 — CRUD: Stylist module (bmp-salon)

**Order in Phase 1:** 6 of 9
**Difficulty:** Medium
**Type:** Feature   **Priority:** High
**Suggested owner:** Achyuth
**Target date:** 2026-07-21
**Depends on:** BMP-23 (same migration, needs salon first)

## One-line summary

CRUD for stylist, stylist_salon, stylist_service — the portable-identity tables. Same V003 migration as BMP-23.

---

## Full Task Detail

### Where this lives

- **Service:** bmp-salon   SCHEMA: salon_schema   MIGRATION: same V003__salon_schema.sql as BMP-23 — coordinate so stylist/stylist_salon/stylist_service/salon_staff/staff_invites land in the same file, not a separate one.
- **Files:** bmp-salon/src/main/java/com/bmp/salon/internal/Stylist.java, StylistSalon.java, StylistService.java, StylistController.java

COLUMNS (from CONTEXT.md, locked):
  stylist: id UUID PK, user_id UUID NULL (nullable — salon can quick-add a stylist before they claim their profile), name, overall_rating NUMERIC (cross-salon lifetime), total_reviews INT, is_top_stylist BOOLEAN (computed: total_reviews>=50 AND overall_rating>=4.7), created_at. Rows are NEVER deleted, only marked alumni via stylist_salon.
  stylist_salon: id UUID PK, stylist_id FK, salon_id FK, status ENUM('active','alumni'), salon_rating NUMERIC (frozen when alumni), salon_review_count INT (frozen when alumni), is_available_today BOOLEAN (the fast kill-switch, flipped in one tap), joined_at, left_at NULL.
  stylist_service: id UUID PK, stylist_id FK, salon_id FK, service_id FK->salon_service.id, actual_duration_minutes INT (real speed, NEVER shown to customer — only salon_service.duration_minutes is shown), override_price_paise BIGINT NULL (per-stylist pricing override).

### Endpoints

#### Endpoint 1: `POST /api/v1/stylists`

   Request: { "name":"Priya Sharma", "userId":"uuid-or-null" }
   Response 201: { "id":"uuid","name":"Priya Sharma","userId":null,"overallRating":0,"totalReviews":0,"isTopStylist":false,"createdAt":"..." }

#### Endpoint 2: `GET /api/v1/stylists/{stylistId}`

   Response 200: full stylist object (same shape as above).
   404 if not found — note stylist rows are never hard-deleted, so a "not found" is genuinely invalid, never "removed".

#### Endpoint 3: `POST /api/v1/salons/{salonId}/stylists`

(link a stylist to a salon — creates stylist_salon)
   Request: { "stylistId":"uuid" }
   Response 201: { "id":"uuid","stylistId":"uuid","salonId":"uuid","status":"active","isAvailableToday":true,"joinedAt":"..." }

#### Endpoint 4: `GET /api/v1/salons/{salonId}/stylists?status=active`

   Response 200: [ { "stylistSalonId":"uuid","stylistId":"uuid","name":"Priya Sharma","status":"active","isAvailableToday":true,"salonRating":4.8 }, ... ]

#### Endpoint 5: `PUT /api/v1/salons/{salonId}/stylists/{stylistId}/available-today`

(the quick kill-switch toggle)
   Request: { "isAvailableToday": false }
   Response 200: { "stylistId":"uuid","salonId":"uuid","isAvailableToday":false }

### Note

this single boolean is checked FIRST (cheapest check) before any slot computation in the availability algorithm (BMP-13/14) — get this endpoint fast and correct, it matters more than it looks.

#### Endpoint 6: `POST /api/v1/salons/{salonId}/stylists/{stylistId}/alumni`

(mark alumni — NEVER a DELETE)
   Response 200: { "stylistId":"uuid","salonId":"uuid","status":"alumni","leftAt":"2026-07-21T00:00:00Z","salonRating":4.8,"salonReviewCount":112 }

### Important

There is deliberately NO DELETE endpoint for stylist_salon — status moves to 'alumni' and salon_rating/salon_review_count get frozen at that moment (locked decision).

#### Endpoint 7: `POST /api/v1/salons/{salonId}/stylists/{stylistId}/services`

and   GET .../services
   Request: { "serviceId":"uuid","actualDurationMinutes":40,"overridePricePaise":75000 }
   Response 201: { "id":"uuid","stylistId":"uuid","serviceId":"uuid","actualDurationMinutes":40,"overridePricePaise":75000 }
   This is also what BMP-14 (freeSlotsAnyStylist) filters on: "shows only stylists with active stylist_service row for that service."

### Data Types

overallRating/salonRating = numeric (e.g. 4.8, 1 decimal typical but store as NUMERIC not float in Postgres). actualDurationMinutes = integer. overridePricePaise = integer paise, nullable.

### Acceptance Criteria

  - Alumni transition correctly freezes salon_rating/salon_review_count (verify by changing the live rating after alumni transition and confirming the frozen snapshot doesn't move).
  - No DELETE endpoint exists anywhere on stylist or stylist_salon — confirm by checking the controller has no @DeleteMapping for these resources.
  - is_available_today toggle round-trips in under typical single-digit-ms DB update time (this feeds directly into BMP-15's <5s walk-in requirement later).

### Depends On

BMP-23 (same migration file, salon must exist first).

### Blocks

BMP-25 (booking_service_item.assigned_stylist_id references this), BMP-13/14/15 (availability algorithm reads stylist_salon + stylist_service directly).

---

*This file is self-contained — hand it directly to whoever is building this task.
Update the status on the team tracker spreadsheet (Tasks sheet) when done.*
