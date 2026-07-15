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
