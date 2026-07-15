# BMP-25 — CRUD: Booking module (bmp-booking)

**Order in Phase 1:** 7 of 9
**Difficulty:** Hard
**Type:** Feature   **Priority:** High
**Suggested owner:** Shivam
**Target date:** 2026-07-22
**Depends on:** BMP-23 + BMP-24 (salon/stylist) + BMP-22 (customer)

## One-line summary

Migration V005 (core 4 tables) + basic booking CRUD. Real webhook-driven confirmation is Phase 3 — this ticket allows manual status moves for local testing only.

---

## Full Task Detail

### Where this lives

- **Service:** bmp-booking   SCHEMA: booking_schema   MIGRATION: V005__booking_schema.sql (not yet written; V001's comment scopes it to booking, booking_service_item, booking_events, slot_lock — the other 4 booking tables from CONTEXT.md — booking_disruption, booking_modification, refund_ticket, refund_guard — are NOT in this ticket, flag as a follow-up ticket once refund/disruption flows are being built)
- **Files:** bmp-booking/src/main/java/com/bmp/booking/internal/Booking.java, BookingServiceItem.java, BookingController.java. The state machine class BookingStatus.java already exists per the Maven skeleton (bmp-booking/src/main/java/com/bmp/booking/api/BookingStatus.java) — REUSE it, do not rewrite the transition rules.

COLUMNS (from CONTEXT.md, locked, core fields only):
  booking: id UUID PK, booking_ref VARCHAR UNIQUE (human-readable, e.g. BMP-2026-08291), salon_id FK (real FK, per pivot decision T3), customer_id FK->users.id, status ENUM (PENDING/CONFIRMED/ARRIVED/IN_SERVICE/COMPLETED/CANCELLED/NO_SHOW), final_amount_paise BIGINT, total_refunded_paise BIGINT DEFAULT 0, commission_paise BIGINT, policy_snapshot JSONB, refund_window_open BOOLEAN DEFAULT true, confirmed_at TIMESTAMPTZ NULL, created_at, updated_at.
  booking_service_item: id UUID PK, booking_id FK, service_id (logical ref to salon_schema.salon_service.id), assigned_stylist_id UUID NULL, selection_type ENUM('specific_stylist','any_available','walk_in_pool'), service_start, service_end, name_snapshot, price_paise_snapshot, duration_shown_minutes, actual_duration_minutes, item_status ENUM('active','removed','completed').
  booking_events: id UUID PK, booking_id FK, event_type VARCHAR, actor_type, actor_id, metadata JSONB, created_at. APPEND-ONLY — REVOKE UPDATE, DELETE in this same migration, same pattern as audit_log.
  slot_lock: id UUID PK, stylist_id, booking_id NULL, date, start_time, end_time, expires_at, release_reason ENUM. (Redis is the live lock at runtime — this table is for analytics/tracing per CONTEXT.md; full Redis wiring is more natural in Phase 2 alongside BMP-13, but the TABLE ships now.)

ENDPOINTS (Phase 1 scope — no payment webhook integration yet):

#### Endpoint 1: `POST /api/v1/bookings`

   Request: { "salonId":"uuid","customerId":"uuid","items":[ {"serviceId":"uuid","stylistId":"uuid-or-null","selectionType":"specific_stylist","start":"2026-07-25T10:00:00Z"} ] }
   Response 201: { "id":"uuid","bookingRef":"BMP-2026-00001","status":"PENDING","finalAmountPaise":80000,"items":[ {"id":"uuid","serviceId":"uuid","assignedStylistId":"uuid","status":"active"} ] }
   Server computes final_amount_paise by summing salon_service.price_paise (or stylist_service.override_price_paise if set) for each item, snapshots name/price/duration into booking_service_item per the FROZEN-at-creation rule.

### Note

booking is created as PENDING and STAYS PENDING in this ticket's scope — the PENDING->CONFIRMED transition is documented as "SYSTEM only — Razorpay webhook. NO other actor," so this ticket must NOT expose a generic PUT that lets anyone set status=CONFIRMED. It's fine to leave bookings sitting in PENDING for now; the transition gets wired in Phase 3.

#### Endpoint 2: `GET /api/v1/bookings/{bookingId}`

   Response 200: { "id":"uuid","bookingRef":"...","salonId":"uuid","customerId":"uuid","status":"PENDING","finalAmountPaise":80000,"totalRefundedPaise":0,"items":[...],"createdAt":"..." }

#### Endpoint 3: `GET /api/v1/bookings?customerId=uuid&status=PENDING`

   Response 200: paginated list, same item shape as GET by id (summarized).

#### Endpoint 4: `POST /api/v1/bookings/{bookingId}/cancel`

   Request: { "reason":"customer requested" }
   Response 200: { "id":"uuid","status":"CANCELLED" }
   Implementation: call BookingStatus's existing transition-check method (CONFIRMED->CANCELLED is a valid actor=CUSTOMER transition per the state machine already in the skeleton; PENDING->CANCELLED should also be allowed — confirm this exact case is handled in BookingStatus.java, add it if missing) — must throw IllegalStateException (already the pattern in the repo) on any illegal transition, do not silently allow.
   Also appends a booking_events row (event_type='CANCELLED', actor_type='customer', metadata={"reason":"..."}).

#### Endpoint 5: `GET /api/v1/bookings/{bookingId}/events`

(read the append-only timeline)
   Response 200: [ { "eventType":"CREATED","actorType":"customer","createdAt":"..." }, { "eventType":"CANCELLED","actorType":"customer","metadata":{"reason":"..."},"createdAt":"..." } ]

### Data Types

finalAmountPaise/totalRefundedPaise/commissionPaise/pricePaiseSnapshot = integer paise, never float. status = exact state machine string values (UPPER_SNAKE per the existing BookingStatus.java — confirm exact casing in that file before implementing, do not guess a different casing).

### Acceptance Criteria

  - Every status transition in this ticket's scope goes through the existing BookingStatus.java, not ad-hoc if-checks — write a test that an illegal transition (e.g. PENDING directly to COMPLETED) throws IllegalStateException.
  - booking_events row is written for every status change and for creation, and the table is proven append-only (same REVOKE-privilege manual test as BMP-3).
  - price_paise_snapshot on booking_service_item does NOT change even if salon_service.price_paise is updated afterward (proves the "frozen at booking creation" rule) — write this as an explicit test: change the service price after booking, re-GET the booking, confirm the old price still shows.

### Depends On

BMP-23/BMP-24 (needs real salon_id/stylist_id/service_id to attach bookings to), BMP-22 (needs real customer_id).

### Blocks

BMP-26 (payment_order needs a real booking_id to attach to), and all of Phase 3's webhook-driven confirmation logic.

---

*This file is self-contained — hand it directly to whoever is building this task.
Update the status on the team tracker spreadsheet (Tasks sheet) when done.*
