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
