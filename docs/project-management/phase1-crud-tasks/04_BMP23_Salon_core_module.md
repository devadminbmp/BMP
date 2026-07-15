# BMP-23 — CRUD: Salon core module (bmp-salon)

**Order in Phase 1:** 4 of 9
**Difficulty:** Medium
**Type:** Feature   **Priority:** High
**Suggested owner:** Darshan
**Target date:** 2026-07-20
**Depends on:** none

## One-line summary

Migration V003 (partial) + CRUD for salon, salon_policy, salon_hours, salon_service. Stylist tables are a separate ticket (BMP-24).

---

## Full Task Detail

### Where this lives

- **Service:** bmp-salon   SCHEMA: salon_schema   MIGRATION: V003__salon_schema.sql (not yet written; this ticket covers salon/salon_policy/salon_hours/salon_service — BMP-24 adds stylist/stylist_salon/stylist_service/salon_staff/staff_invites to the SAME V003 file, coordinate so it's one migration not two)
- **Files:** bmp-salon/src/main/java/com/bmp/salon/internal/Salon.java, SalonPolicy.java, SalonHours.java, SalonService.java, SalonController.java

COLUMNS (from CONTEXT.md, locked):
  salon: id UUID PK, name, location GEOGRAPHY(POINT) [PostGIS — NOT lat/lon columns], status ENUM('pending','approved','rejected','suspended','closed'), stylist_assignment_strategy ENUM('random','highest_rated','least_loaded'), created_at, updated_at. Salons are NEVER hard-deleted.
  salon_policy: id UUID PK, salon_id FK, template ENUM('strict','standard','flexible'), free_cancel_hours, late_grace_minutes, require_prepayment BOOLEAN (always true in Phase 1).
  salon_hours: id UUID PK, salon_id FK, day_of_week SMALLINT (0-6), open_time TIME, close_time TIME (7 rows per salon — one per day; close_time means slots must END by this time).
  salon_service: id UUID PK, salon_id FK, name, price_paise BIGINT, duration_minutes INT, requires_stylist_assignment BOOLEAN (false = walk-in pool).

### Endpoints

#### Endpoint 1: `POST /api/v1/salons`

   Request: { "name":"Glow Salon", "location":{"lat":12.9352,"lng":77.6146}, "stylistAssignmentStrategy":"least_loaded" }
   (Server converts lat/lng into PostGIS GEOGRAPHY(POINT) via ST_MakePoint(lng,lat)::geography — note lng THEN lat, standard PostGIS order.)
   Response 201: { "id":"uuid","name":"Glow Salon","status":"pending","stylistAssignmentStrategy":"least_loaded","createdAt":"..." }

#### Endpoint 2: `GET /api/v1/salons/{salonId}`

   Response 200: { "id":"uuid","name":"Glow Salon","location":{"lat":12.9352,"lng":77.6146},"status":"pending","stylistAssignmentStrategy":"least_loaded","createdAt":"...","updatedAt":"..." }
   404 if not found (never hard-deleted, so 404 truly means invalid id, not "was deleted").

#### Endpoint 3: `PUT /api/v1/salons/{salonId}`

   Request: subset of { name, location, status, stylistAssignmentStrategy }
   Response 200: updated object. NOTE: status transitions (pending->approved etc) should eventually go through an admin-only endpoint with audit_log entries (BMP-29/BMP-3) — for this ticket plain PUT is fine, tighten in Phase 3.

#### Endpoint 4: `GET /api/v1/salons?near=12.9352,77.6146&radiusKm=4`

   Response 200: [ { "id":"uuid","name":"Glow Salon","distanceKm":1.2 }, ... ]
   Uses PostGIS ST_DWithin on the GEOGRAPHY column — proximity search per the locked design (spherical, accurate, not flat GEOMETRY).

#### Endpoint 5: `POST /api/v1/salons/{salonId}/policy`

and   GET /api/v1/salons/{salonId}/policy
   Request: { "template":"standard", "freeCancelHours":24, "lateGraceMinutes":15, "requirePrepayment":true }
   Response 201/200: same shape with "id" and "salonId" added.

#### Endpoint 6: `PUT /api/v1/salons/{salonId}/hours`

(bulk upsert all 7 days at once)
   Request: { "hours":[ {"dayOfWeek":0,"openTime":"10:00","closeTime":"21:00"}, ... 7 entries ... ] }
   Response 200: { "salonId":"uuid","hours":[ ...same shape echoed back... ] }
   Validation: exactly 0-7 entries, dayOfWeek 0-6 unique, closeTime > openTime.

#### Endpoint 7: `POST /api/v1/salons/{salonId}/services`

and   GET /api/v1/salons/{salonId}/services
   Request: { "name":"Haircut","pricePaise":80000,"durationMinutes":45,"requiresStylistAssignment":true }
   Response 201: { "id":"uuid","salonId":"uuid","name":"Haircut","pricePaise":80000,"durationMinutes":45,"requiresStylistAssignment":true }
   NOTE pricePaise is INTEGER paise (₹800 = 80000), never a float — Schema Rule #1.

### Data Types

pricePaise = BIGINT/integer (paise). durationMinutes/freeCancelHours/lateGraceMinutes = integer. location = {lat: number, lng: number} in JSON, GEOGRAPHY(POINT) in DB. dayOfWeek = integer 0-6. openTime/closeTime = "HH:mm" string.

### Acceptance Criteria

  - PostGIS extension confirmed enabled (already done in V001 — CREATE EXTENSION IF NOT EXISTS postgis).
  - Proximity search (#4) returns correct distance-sorted results against 3+ seeded salons at known coordinates.
  - salon.status enum rejects invalid values with 400, not a raw DB constraint error.
  - salon_hours upsert is idempotent (calling twice with the same body doesn't create duplicate day rows).

### Depends On

none.

### Blocks

BMP-24 (stylist tables reference salon_id), BMP-25 (booking references salon_service), BMP-13/14/15 (availability reads salon_hours).

---

*This file is self-contained — hand it directly to whoever is building this task.
Update the status on the team tracker spreadsheet (Tasks sheet) when done.*
