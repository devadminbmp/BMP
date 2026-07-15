# BMP Phase 1 — CRUD Task Files

9 tasks. Each does ONE thing: plain create/read/update/list REST endpoints for one
service's own database tables. No third-party APIs (Razorpay/MSG91/FCM), no calls to
other modules, no login/auth yet — all of that is Phase 3, later. Work top to bottom;
the order below is easiest first, and later tasks depend on earlier ones.

| # | Task ID | File | What it builds | Difficulty | Owner | Depends on |
|---|---|---|---|---|---|---|
| 1 | BMP-30 | `01_BMP30_Notification_log.md` | bmp-notification | Easy | Shivam | BMP-6 (table design) |
| 2 | BMP-29 | `02_BMP29_Admin_module.md` | bmp-admin | Medium | Achyuth | BMP-4 (V008 migration) |
| 3 | BMP-22 | `03_BMP22_User_module.md` | bmp-user | Medium | Achyuth | none |
| 4 | BMP-23 | `04_BMP23_Salon_core_module.md` | bmp-salon | Medium | Darshan | none |
| 5 | BMP-28 | `05_BMP28_Rewards_module.md` | bmp-rewards | Medium | Achyuth | BMP-22 (needs a real user_id) |
| 6 | BMP-24 | `06_BMP24_Stylist_module.md` | bmp-salon | Medium | Achyuth | BMP-23 (same migration, needs salon first) |
| 7 | BMP-25 | `07_BMP25_Booking_module.md` | bmp-booking | Hard | Shivam | BMP-23 + BMP-24 (salon/stylist) + BMP-22 (customer) |
| 8 | BMP-26 | `08_BMP26_Payment_order_module.md` | bmp-payment | Medium | Shivam | BMP-25 (needs a real booking_id) |
| 9 | BMP-27 | `09_BMP27_Review_module.md` | bmp-review | Medium | Darshan | BMP-25 (needs a real booking_id) |

---

# BMP-30 — CRUD: Notification log (bmp-notification)

**Order in Phase 1:** 1 of 9
**Difficulty:** Easy
**Type:** Feature   **Priority:** Low
**Suggested owner:** Shivam
**Target date:** 2026-07-18
**Depends on:** BMP-6 (table design)

## One-line summary

Migration V009 + a create/list API for notification_log entries — no actual WhatsApp/push send yet (that's Phase 3).

---

## Full Task Detail

### Where this lives

- **Service:** bmp-notification   SCHEMA: notification_schema   MIGRATION: V009__notification_schema.sql (see note on BMP-6 — this number isn't in V001's original comment, proposed as the next free slot after V008 admin_schema; confirm with team).
- **Files:** bmp-notification/src/main/java/com/bmp/notification/internal/NotificationLogController.java

This ticket is intentionally minimal — the real value (actually sending WhatsApp/push) is Phase 3 (BMP-7/BMP-8/BMP-9). Phase 1 just needs the log table to exist and be writable/readable so other Phase-1 tickets (e.g. review prompts, booking confirmations) have somewhere to record "a notification should have been sent here" even before the real send integration exists.

### Endpoints

#### Endpoint 1: `POST /api/v1/notifications/log`

(called internally by other modules for now, not a real send — just records intent)
   Request: { "recipientUserId":"uuid","channel":"whatsapp","templateCode":"BOOKING_CONFIRMED","payload":{"salonName":"Glow Salon","bookingRef":"BMP-2026-00891","time":"5:30 PM"} }
   Response 201: { "id":"uuid","recipientUserId":"uuid","channel":"whatsapp","templateCode":"BOOKING_CONFIRMED","status":"queued","createdAt":"..." }

### Note

status stays 'queued' forever in this ticket's scope — nothing moves it to 'sent'/'delivered'/'failed' until BMP-8/BMP-9 exist in Phase 3. This is expected and fine.

#### Endpoint 2: `GET /api/v1/notifications/log?recipientUserId=uuid&channel=whatsapp`

   Response 200: [ { "id":"uuid","channel":"whatsapp","templateCode":"BOOKING_CONFIRMED","status":"queued","payload":{...},"createdAt":"..." }, ... ]

#### Endpoint 3: `GET /api/v1/notifications/log/{id}`

   Response 200: single entry, same shape.

### Data Types

payload = JSONB (arbitrary object), channel/status/templateCode = string enums per BMP-6's locked columns.

### Acceptance Criteria

  - Table + endpoints exist and round-trip correctly.
  - Explicitly documented (in code comment and in this ticket) that NO actual message is sent yet — status='queued' is the expected terminal state until Phase 3.

### Depends On

BMP-6 (table design).

### Blocks

nothing functionally in Phase 1 — becomes load-bearing once BMP-7 (OutboxProcessor) exists in Phase 3 to actually drain this queue.

---

*This file is self-contained — hand it directly to whoever is building this task.
Update the status on the team tracker spreadsheet (Tasks sheet) when done.*



# BMP-29 — CRUD: Admin module (bmp-admin)

**Order in Phase 1:** 2 of 9
**Difficulty:** Medium
**Type:** Feature   **Priority:** Medium
**Suggested owner:** Achyuth
**Target date:** 2026-07-18
**Depends on:** BMP-4 (V008 migration)

## One-line summary

Basic CRUD REST layer on top of the V008 tables from BMP-4 (bmp_staff, support_ticket, support_message). audit_log is read + system-insert only.

---

## Full Task Detail

### Where this lives

- **Service:** bmp-admin   SCHEMA: admin_schema   MIGRATION: uses V008__admin_schema.sql already written in BMP-4 — this ticket is the REST layer on top, no new migration.
- **Files:** bmp-admin/src/main/java/com/bmp/admin/internal/BmpStaffController.java, SupportTicketController.java, AuditLogController.java

### Endpoints

#### Endpoint 1: `POST /api/v1/staff`

   Request: { "name":"Ops Person","phone":"+919876500000","email":"ops@bmp.app","passwordHash":"<bcrypt-hash-computed-server-side-from-a-plaintext-password-field-do-not-accept-hash-from-client>","role":"ops_admin" }

### Correction

client sends plaintext "password" field, server bcrypt-hashes it before storing — never accept a pre-hashed password from a client. Response 201: { "id":"uuid","name":"Ops Person","phone":"...","role":"ops_admin","status":"active","createdAt":"..." } (never return password_hash in any response).

#### Endpoint 2: `GET /api/v1/staff/{staffId}`

and   GET /api/v1/staff?role=support_agent
   Response 200: staff object(s), password_hash always excluded from the JSON.

#### Endpoint 3: `PUT /api/v1/staff/{staffId}/status`

   Request: { "status":"suspended" }
   Response 200: updated object. This action MUST also write an admin_schema.audit_log row (action='staff.status_changed', entity_type='bmp_staff', metadata={"before":"active","after":"suspended"}) — this is the first real caller of the audit_log table, wire it here.

#### Endpoint 4: `POST /api/v1/support-tickets`

   Request: { "raisedByType":"customer","raisedById":"uuid","bookingId":"uuid-or-null","category":"booking_issue","subject":"Stylist didn't show up" }
   Response 201: { "id":"uuid","ticketRef":"TCK-2026-00001","status":"open","priority":"medium","createdAt":"..." }
   ticketRef generation: use a Postgres sequence formatted as TCK-YYYY-NNNNN (per BMP-2's design note).

#### Endpoint 5: `GET /api/v1/support-tickets/{ticketId}`

and   GET /api/v1/support-tickets?status=open&assignedStaffId=uuid
   Response 200: ticket object(s) with nested recent messages optionally via ?includeMessages=true.

#### Endpoint 6: `PUT /api/v1/support-tickets/{ticketId}`

   Request: subset of { status, priority, assignedStaffId }
   Response 200: updated object. Status change to 'resolved' should set resolved_at.

#### Endpoint 7: `POST /api/v1/support-tickets/{ticketId}/messages`

   Request: { "senderType":"bmp_staff","senderId":"uuid","message":"We've refunded your booking.","attachmentUrl":null }
   Response 201: { "id":"uuid","ticketId":"uuid","senderType":"bmp_staff","message":"...","createdAt":"..." }

#### Endpoint 8: `GET /api/v1/audit-log?entityType=bmp_staff&entityId=uuid`

(READ-ONLY — no POST/PUT/DELETE exposed publicly; rows are only ever inserted as a side-effect of other actions like #3 above)
   Response 200: [ { "action":"staff.status_changed","actorType":"bmp_staff","actorId":"uuid","metadata":{...},"createdAt":"..." }, ... ]

### Data Types

all ids = UUID. status/role/category/priority = string enums exactly per BMP-1/BMP-2's locked column definitions.

### Acceptance Criteria

  - password_hash NEVER appears in any JSON response (grep the response DTOs to confirm the field is excluded, not just "usually" omitted).
  - Staff status change produces exactly one audit_log row with correct before/after metadata.
  - No DELETE endpoint exists on staff or audit_log.
  - ticketRef is unique and correctly sequential per year.

### Depends On

BMP-4 (V008 migration must exist).

### Blocks

none in Phase 1 — this is the last piece admin-side before Phase 3 auth work can layer staff login on top of bmp_staff.

---

*This file is self-contained — hand it directly to whoever is building this task.
Update the status on the team tracker spreadsheet (Tasks sheet) when done.*



# BMP-22 — CRUD: User module (bmp-user)

**Order in Phase 1:** 3 of 9
**Difficulty:** Medium
**Type:** Feature   **Priority:** High
**Suggested owner:** Achyuth
**Target date:** 2026-07-19
**Depends on:** none

## One-line summary

Migration V002 + basic profile CRUD for users/user_roles. OTP login itself is out of scope here (Phase 3).

---

## Full Task Detail

### Where this lives

- **Service:** bmp-user   SCHEMA: user_schema   MIGRATION: V002__user_schema.sql (not yet written — confirmed empty in repo; write it as part of this ticket)
- **Files:** bmp-user/src/main/java/com/bmp/user/internal/User.java (entity), UserRole.java (entity), UserController.java (REST layer), UserRepository.java

STEP 0 — MIGRATION (V002__user_schema.sql): create user_schema.users, user_schema.user_roles, user_schema.otp_requests, user_schema.refresh_tokens, user_schema.onboarding_state per the exact columns already locked in CONTEXT.md Module 1: User. This ticket only builds CRUD on top of users + user_roles; otp_requests/refresh_tokens/onboarding_state tables should still be created now (so V002 is complete in one migration) but their CRUD/logic is Phase 3 (BMP-31).

users columns (from CONTEXT.md, locked): id UUID PK UUIDv7, phone VARCHAR UNIQUE NOT NULL (E.164), name, gender, age, email, profile_photo_url, hair_type, hair_length, default_role, is_verified BOOLEAN, created_at, updated_at.
user_roles columns: id UUID PK, user_id UUID FK->users.id, role ENUM('customer','stylist','salon_owner','manager'), salon_id UUID NULL (logical ref to salon_schema.salon.id, NULL for customer role).

ENDPOINTS (this ticket — plain CRUD, no OTP/auth middleware yet, so treat as an open internal API for now; auth is bolted on in Phase 3 BMP-32):

#### Endpoint 1: `POST /api/v1/users`

   Request: { "phone":"+919876543210", "name":"Ravi Kumar", "gender":"male", "age":29, "email":"ravi@example.com", "defaultRole":"customer" }
   Response 201: { "id":"uuid", "phone":"+919876543210", "name":"Ravi Kumar", "isVerified":false, "createdAt":"2026-07-19T10:00:00Z" }
   Response 409 if phone already exists: { "error":"PHONE_ALREADY_EXISTS" }

#### Endpoint 2: `GET /api/v1/users/{userId}`

   Response 200: { "id":"uuid","phone":"...","name":"...","gender":"male","age":29,"email":"...","profilePhotoUrl":null,"hairType":null,"hairLength":null,"defaultRole":"customer","isVerified":false,"createdAt":"...","updatedAt":"..." }
   Response 404: { "error":"USER_NOT_FOUND" }

#### Endpoint 3: `GET /api/v1/users?phone=%2B919876543210`

(lookup by phone, URL-encoded)
   Same response shape as #2, or 404.

#### Endpoint 4: `PUT /api/v1/users/{userId}`

   Request: any subset of { name, gender, age, email, profilePhotoUrl, hairType, hairLength } — phone is IMMUTABLE via this endpoint (it's the identity key).
   Response 200: full updated object (same shape as GET).

#### Endpoint 5: `POST /api/v1/users/{userId}/roles`

   Request: { "role":"stylist", "salonId":"uuid-or-null" }
   Response 201: { "id":"uuid","userId":"uuid","role":"stylist","salonId":"uuid" }

#### Endpoint 6: `GET /api/v1/users/{userId}/roles`

   Response 200: [ { "id":"uuid","role":"customer","salonId":null }, { "id":"uuid","role":"stylist","salonId":"uuid-x"} ]
   (Proves the documented case: "A stylist who also books as a customer has ONE users row and TWO user_roles rows.")

### Data Types

all ids = UUID string (UUIDv7). age = integer. isVerified = boolean. gender/role/defaultRole = string enums exactly as listed above. Timestamps = ISO-8601 with Z.

### Validation

phone must match E.164 regex (^\+[1-9]\d{7,14}$); unique constraint enforced at DB AND checked in service layer for a clean 409 instead of a raw DB error.

### Acceptance Criteria

  - V002 migration creates all 5 user_schema tables with exact CONTEXT.md columns.
  - All 6 endpoints above pass manual Postman/curl testing.
  - Duplicate phone returns 409, not a 500.
  - A user with 2 roles round-trips correctly through GET .../roles.

### Depends On

none (schema already locked in CONTEXT.md, just needs to be migrated).

### Blocks

BMP-31 (OTP auth, Phase 3) reuses this table; BMP-25 (Booking CRUD) needs a real user_id to attach bookings to.

---

*This file is self-contained — hand it directly to whoever is building this task.
Update the status on the team tracker spreadsheet (Tasks sheet) when done.*



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



# BMP-28 — CRUD: Rewards module (bmp-rewards)

**Order in Phase 1:** 5 of 9
**Difficulty:** Medium
**Type:** Feature   **Priority:** Medium
**Suggested owner:** Achyuth
**Target date:** 2026-07-20
**Depends on:** BMP-22 (needs a real user_id)

## One-line summary

Migration V007 (rewards half) + CRUD for coupon, wallet, referral_code. wallet_transaction is append-only, read-only via API.

---

## Full Task Detail

### Where this lives

- **Service:** bmp-rewards   SCHEMA: rewards_schema   MIGRATION: same V007 migration file as BMP-27 (or its own file in the same numeric slot — coordinate with whoever does BMP-27 first)
- **Files:** bmp-rewards/src/main/java/com/bmp/rewards/internal/Coupon.java, Wallet.java, WalletTransaction.java, ReferralCode.java, RewardsController.java

COLUMNS (from CONTEXT.md Module 6, core fields):
  coupon: id UUID PK, code VARCHAR UNIQUE, type ENUM('welcome','referral','loyalty','off_peak','festival','birthday','win_back','salon_specific'), salon_id NULL (null = platform-wide), commission_base ENUM('pre_discount','post_discount') — salon_specific coupons ALWAYS pre_discount (locked rule), discount_type/value, min_spend_paise, per_user_limit, total_usage_cap, active_from, active_to, allows_wallet_stacking BOOLEAN.
  wallet: id UUID PK, user_id UNIQUE, balance_paise BIGINT NOT NULL DEFAULT 0 (never negative — enforce with a CHECK constraint), is_frozen BOOLEAN DEFAULT false.
  wallet_transaction: id UUID PK, wallet_id FK, type (one of 9 transaction types per CONTEXT.md), amount_paise BIGINT (signed), balance_after_paise BIGINT (snapshot), created_at. APPEND-ONLY — REVOKE UPDATE, DELETE, same pattern as audit_log/booking_events.
  referral_code: id UUID PK, user_id UNIQUE, code VARCHAR UNIQUE (format e.g. DARSHAN-X7K), created_at.

### Endpoints

#### Endpoint 1: `POST /api/v1/coupons`

(admin creates a coupon)
   Request: { "code":"WELCOME100","type":"welcome","salonId":null,"commissionBase":"post_discount","discountType":"flat","value":10000,"minSpendPaise":50000,"perUserLimit":1,"totalUsageCap":1000,"activeFrom":"2026-07-01","activeTo":"2026-12-31","allowsWalletStacking":true }
   Response 201: full object with generated id.
   NOTE value/minSpendPaise in paise (integer), never float.

#### Endpoint 2: `POST /api/v1/coupons/validate`

(the 6-rule check, per CONTEXT.md — implement all 6 even in this basic CRUD pass, they're pure logic not integration)
   Request: { "code":"WELCOME100","userId":"uuid","salonId":"uuid","subtotalPaise":80000 }
   Response 200 valid: { "valid":true,"couponId":"uuid","discountPaise":10000,"commissionBase":"post_discount" }
   Response 200 invalid: { "valid":false,"reason":"MIN_SPEND_NOT_MET" }  (reason = one of: INACTIVE_OR_EXPIRED, SALON_MISMATCH, PER_USER_LIMIT_EXCEEDED, MIN_SPEND_NOT_MET, TOTAL_CAP_EXCEEDED, NOT_FIRST_BOOKING)
   Run the 6 checks IN THE DOCUMENTED ORDER from CONTEXT.md, return the FIRST failing reason (not all failures at once, matches "specific error per failure" locked decision).

#### Endpoint 3: `GET /api/v1/users/{userId}/wallet`

   Response 200: { "userId":"uuid","balancePaise":15000,"isFrozen":false }

#### Endpoint 4: `GET /api/v1/users/{userId}/wallet/transactions?page=0&size=20`

   Response 200: paginated, read-only: [ {"id":"uuid","type":"referral_credit","amountPaise":15000,"balanceAfterPaise":15000,"createdAt":"..."} ]
   NO PUT/DELETE on wallet_transaction anywhere — it's append-only, same DB-level REVOKE as audit_log. New transactions are only ever created as a side-effect of other flows (referral, booking completion, refund) — not directly POSTed by a client in this ticket. If you need a manual test credit, add a clearly-marked ADMIN-ONLY /api/v1/admin/wallet/credit endpoint instead of exposing a generic POST.

#### Endpoint 5: `POST /api/v1/users/{userId}/referral-code`

(idempotent — generate once)
   Response 201 or 200 if already exists: { "userId":"uuid","code":"DARSHAN-X7K","createdAt":"..." }

### Data Types

all *Paise fields = integer, never float. balancePaise must be enforced >= 0 at the DB level (CHECK constraint), not just app-level.

### Acceptance Criteria

  - coupon/validate runs all 6 rules in the documented order and returns the correct FIRST failure reason for a coupon deliberately violating rule 3 and rule 5 simultaneously (should report rule 3's reason, since it's checked first).
  - wallet.balance_paise CHECK constraint proven: attempt a manual negative-balance UPDATE at the DB level, confirm it's rejected.
  - wallet_transaction table proven append-only (same REVOKE-privilege manual test as BMP-3/BMP-25).
  - salon_specific coupon always resolves commission_base='pre_discount' regardless of what's requested in the create payload (server-side enforced, not client-trusted).

### Depends On

BMP-22 (needs real user_id for wallet).

### Blocks

none in Phase 1 — coupon consumption during actual checkout and referral-triggered wallet credit are booking-flow integrations, later tickets.

---

*This file is self-contained — hand it directly to whoever is building this task.
Update the status on the team tracker spreadsheet (Tasks sheet) when done.*



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



# BMP-26 — CRUD: Payment order module (bmp-payment)

**Order in Phase 1:** 8 of 9
**Difficulty:** Medium
**Type:** Feature   **Priority:** Medium
**Suggested owner:** Shivam
**Target date:** 2026-07-21
**Depends on:** BMP-25 (needs a real booking_id)

## One-line summary

Migration V006 (payment_order only) + basic CRUD, no real Razorpay call yet — order id is a local stub until Phase 3.

---

## Full Task Detail

### Where this lives

- **Service:** bmp-payment   SCHEMA: payment_schema   MIGRATION: V006__payment_schema.sql

### Important Discrepancy Found

V001's trailing comment names this migration's tables as "payment, payout_record, payout_queue, bank_account" — but CONTEXT.md's Session-3 locked design (more recent, explicitly "the single source of truth") specifies 9 tables with different names: payment_order, webhook_event, razorpay_linked_account, payout_queue_item, payout_batch, commission_ledger, refund_execution, bmp_account, saas_subscription, saas_invoice. Flag this to the team — recommend following CONTEXT.md's fuller schema since it's the newer, detailed, explicitly-locked version. This ticket only builds the ONE core table needed for basic CRUD (payment_order); the other 8 tables are follow-up tickets once Razorpay integration (Phase 3, BMP-19) is actually being wired.

FILES: bmp-payment/src/main/java/com/bmp/payment/internal/PaymentOrder.java, PaymentOrderController.java

COLUMNS (payment_order, from CONTEXT.md Module 4, core fields):
  id UUID PK, booking_id FK->booking_schema.booking.id (real FK), razorpay_order_id VARCHAR UNIQUE NULL (stub/null in this ticket — real value only comes from the actual Razorpay call in Phase 3), idempotency_key VARCHAR (booking_id + attempt_number), commission_paise BIGINT (frozen at creation), salon_share_paise BIGINT (frozen at creation), amount_paise BIGINT, status ENUM('created','captured','failed') — simplified 3-state for Phase 1, full webhook-driven states added in Phase 3, payment_captured_at TIMESTAMPTZ NULL (webhook-only, stays NULL in this ticket), created_at.

ENDPOINTS (Phase 1 — data model only, NO real Razorpay API call):

#### Endpoint 1: `POST /api/v1/bookings/{bookingId}/payment-order`

   Request: {} (no body needed — amount is derived from the booking)
   Response 201: { "id":"uuid","bookingId":"uuid","amountPaise":80000,"commissionPaise":9600,"salonSharePaise":70400,"razorpayOrderId":null,"status":"created","createdAt":"..." }
   Server logic: fetch booking.final_amount_paise, compute commission_paise = 12% of amount (per locked 88/12 split), salon_share_paise = amount - commission. razorpay_order_id stays NULL — a TODO comment should mark exactly where the real Razorpay create-order call goes in Phase 3 (BMP-19/payment integration ticket).

#### Endpoint 2: `GET /api/v1/payment-orders/{paymentOrderId}`

   Response 200: same shape as above.

#### Endpoint 3: `GET /api/v1/bookings/{bookingId}/payment-order`

   Response 200: same shape, or 404 if none created yet.

#### Endpoint 4: `PUT /api/v1/payment-orders/{paymentOrderId}/status`

(MANUAL, DEV-ONLY endpoint — clearly mark as temporary)
   Request: { "status":"captured" }
   Response 200: updated object.
   EXPLICIT WARNING to put in the code as a comment: this manual status-set endpoint EXISTS ONLY so booking flows can be tested locally without a live Razorpay webhook. It directly violates the locked rule "Razorpay webhook = ONLY source of payment truth" if left reachable in production — either feature-flag it off outside local/dev profiles, or delete it the moment BMP-19/Phase 3 wires the real webhook.

### Data Types

all *_paise fields = integer (BIGINT), never float. status = string enum, simplified set for this ticket only.

### Acceptance Criteria

  - commission_paise + salon_share_paise always exactly equals amount_paise (no rounding leak) — write a test with an odd amount (e.g. 80001 paise) and confirm the split still sums correctly.
  - The dev-only manual status endpoint is clearly flagged/guarded, not indistinguishable from a real endpoint.
  - idempotency_key prevents creating two payment_order rows for the same booking_id + attempt (unique constraint or explicit check returning 409).

### Depends On

BMP-25 (needs a real booking_id).

### Blocks

none in Phase 1; Phase 3's Razorpay integration (BMP-19) replaces the stub order-creation logic and wires the real webhook to flip status.

---

*This file is self-contained — hand it directly to whoever is building this task.
Update the status on the team tracker spreadsheet (Tasks sheet) when done.*



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



