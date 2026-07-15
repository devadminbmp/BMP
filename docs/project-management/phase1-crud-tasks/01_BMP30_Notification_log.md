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
