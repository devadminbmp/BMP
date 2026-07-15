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
