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
