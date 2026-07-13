# BMP (Be My Professional) — Complete Project Context

> **For AI Agents:** Read this entire file before responding to anything. This is the single source of truth for the BMP project. Every architectural decision, every schema rule, every product decision documented here is LOCKED. Do not re-suggest alternatives that were already evaluated and rejected. Jump straight into helping with the task at hand.
>
> **For Team Members:** Update the [Session Log](#session-log) section when you complete a session. Add your decisions to [Locked Decisions](#locked-decisions). Never change locked decisions without a full team discussion.

---

## Table of Contents

1. [What is BMP](#what-is-bmp)
2. [Team](#team)
3. [Current Status](#current-status)
4. [Technology Stack — LOCKED](#technology-stack--locked)
5. [Architecture — LOCKED](#architecture--locked)
6. [Module Overview](#module-overview)
7. [Schema Rules — NEVER BREAK THESE](#schema-rules--never-break-these)
8. [Module 1: User](#module-1-user)
9. [Module 2: Salon](#module-2-salon)
10. [Module 3: Booking](#module-3-booking)
11. [Module 4: Payment](#module-4-payment)
12. [Module 5: Review](#module-5-review)
13. [Module 6: Rewards](#module-6-rewards)
14. [Locked Product Decisions](#locked-product-decisions)
15. [What to Build Next](#what-to-build-next)
16. [Documents and Files](#documents-and-files)
17. [Full Session Log — Every Chat Turn Summarised](#full-session-log--every-chat-turn-summarised)
18. [How to Add to This File](#how-to-add-to-this-file)

---

## What is BMP

**Be My Professional** is a premium salon booking platform targeting Bengaluru, India. Customers discover, book, and pre-pay for salon services. Salons manage their calendar, stylists, and payouts. Stylists own a portable verified identity that travels with them across salons.

### The Problem BMP Solves

Every Indian salon marketplace before BMP (Fabogo, Vyomo, Bulbul, Zoylee) died because of the **calendar-truth problem**: salons listed availability they didn't honour, customers showed up for appointments that didn't exist, trust collapsed. BMP's entire architecture is built around making the calendar true.

### Target Market

- **Phase 1:** Premium salons only — ₹800+ average ticket, top 300-500 salons in Bengaluru
- **Geography:** Koramangala / HSR Layout / Indiranagar cluster (4km radius) to start
- **Why premium only:** ₹20 gross margin at ₹300 tickets is unworkable. ₹140+ at premium is viable. ₹1,200-3,200 at bridal is the real business.

### Revenue Model

1. **Discovery commission:** 8-12% on first 3 visits (new customer to that salon)
2. **SaaS fee:** ₹1,500/month (Standard) or ₹3,000/month (Premium) after pilot
3. **Bridal:** 5-8% on bridal bookings (separate flow, high value)

### BMP's Differentiation

- **Portable stylist identity** — Ravi Kumar's verified reviews and ratings follow him when he moves salons. His employer cannot take his reputation. No competitor has this.
- **Verified reviews** — every review requires a completed, paid booking ID. No fake reviews possible.
- **Calendar truth** — appointment blocking is real, not aspirational.

---

## Team

- **3 co-founders** working across different laptops and AI tools
- Tools in use: Claude (claude.ai), IntelliJ IDEA with Claude plugin, Anti-Gravity (other AI tool)
- Repository: `bmp-platform` (private GitHub repo)
- **This CONTEXT.md is the shared brain** — load it at the start of every AI session

---

## Current Status

**Phase: Schema Design Complete → Starting Backend Development**

| Area | Status |
|---|---|
| Product strategy and GTM | ✅ LOCKED |
| UX/UI design (60+ screens) | ✅ COMPLETE |
| All 6 core module schemas | ✅ COMPLETE |
| Maven multi-module skeleton | ✅ CREATED |
| Admin module schema | ⏳ NEXT |
| Notification module schema | ⏳ NEXT |
| Availability model implementation | ⏳ HIGHEST TECHNICAL PRIORITY |
| LLD / API contracts | ⏳ PENDING |
| Razorpay Route confirmation | ⏳ PENDING (confirm directly with Razorpay) |
| Backend development | 🔜 NOT STARTED |

---

## Technology Stack — LOCKED

| Layer | Choice | Why |
|---|---|---|
| Language | Java 21 | LTS, virtual threads, records |
| Framework | Spring Boot 3.4.1 + Spring Modulith | Enforces module boundaries at build time |
| Architecture | Multi-module Maven monolith | 3-person team, pre-PMF, real FKs, one deploy |
| Primary DB | PostgreSQL 16 + PostGIS 3.4 | Relational integrity + geospatial proximity search |
| Document DB | MongoDB 7 | Community feed only — not general purpose |
| Cache / Locks | Redis | Slot locks during checkout only |
| Async events | Postgres Outbox + relay worker | Replaces Kafka — simpler, same guarantees |
| Payments | Razorpay Route | BMP never holds funds — splits at capture |
| File storage | Cloudflare R2 | Before/after photos, salon photos, stylist photos |
| WhatsApp | MSG91 | OTP + booking notifications + review prompts |
| Boundary tests | ArchUnit + Spring Modulith verify() | Build fails if module boundaries are violated |

**DO NOT SUGGEST:** Kafka (replaced by outbox), microservices (using modular monolith), BigDecimal for money (integer paise only), separate DBs per module (one Postgres, multiple schemas).

---

## Architecture — LOCKED

### Project Structure

```
bmp-platform/                    ← Parent Maven POM
├── bmp-common/                  ← Shared kernel (Money, UUIDv7, DomainEvent, Outbox)
├── bmp-user/                    ← Auth, OTP, profiles, roles
├── bmp-salon/                   ← Salons, stylists, services, availability model ⭐
├── bmp-booking/                 ← Bookings, slot locks, state machine
├── bmp-payment/                 ← Razorpay Route, webhooks, payouts, commission
├── bmp-review/                  ← Reviews, ratings, community feed bridge
├── bmp-rewards/                 ← Coupons, wallet, referrals, loyalty
├── bmp-admin/                   ← bmp_staff, support tickets, audit log (PENDING)
├── bmp-notification/            ← Outbox processor → WhatsApp/SMS/push
└── bmp-app/                     ← ONE Spring Boot deployable, Flyway migrations
```

### Module Rules (enforced by ArchUnit — build fails if violated)

1. Modules NEVER import from another module's `internal` package
2. Cross-module communication: public Java interface OR domain events via outbox
3. Each module owns its own PostgreSQL schema (`user_schema`, `salon_schema`, etc.)
4. Cross-schema FKs are logical (enforced by application), not DB constraints
5. `bmp_staff` table is COMPLETELY SEPARATE from `users` — different auth, different table

### Outbox Pattern (Kafka replacement)

```
Service writes to DB + inserts outbox_entry in SAME transaction
→ OutboxProcessor (scheduled, SKIP LOCKED) reads outbox
→ Publishes domain event to consuming module
→ Consuming module processes and marks outbox entry done
```

This gives exactly-once semantics without Kafka operational overhead.

---

## Module Overview

| Module | Schema | Tables | Technology |
|---|---|---|---|
| User | user_schema | 5 | PostgreSQL |
| Salon | salon_schema | 12 | PostgreSQL + PostGIS |
| Booking | booking_schema | 8 | PostgreSQL + Redis |
| Payment | payment_schema | 9 | PostgreSQL |
| Review | review_schema + community_db | 6 PG + 5 Mongo | PostgreSQL + MongoDB |
| Rewards | rewards_schema | 10 | PostgreSQL |
| Admin | admin_schema | 4 (PENDING) | PostgreSQL |
| Notification | stateless | 1 log table | PostgreSQL |

---

## Schema Rules — NEVER BREAK THESE

These are architectural constraints baked into the codebase. Violating them breaks financial integrity.

```
🔒 Money = BIGINT paise (integer). ₹1 = 100 paise. NEVER floats or decimals.
🔒 All PKs = UUIDv7 (time-ordered). Never sequential integers.
🔒 Razorpay webhook = ONLY source of payment truth. Never trust client SDK callback.
🔒 BMP never holds customer funds. Razorpay Route splits at capture (88% salon, 12% BMP).
🔒 Price, duration, policy FROZEN at booking creation time. Salon changes don't affect existing bookings.
🔒 Stylist identity is PORTABLE and PERMANENT. stylist rows never deleted, only marked alumni.
🔒 audit_log and booking_events are APPEND-ONLY. DELETE and UPDATE permissions REVOKED at DB level.
🔒 bmp_staff is SEPARATE from users. Zero shared auth infrastructure.
🔒 ArchUnit bans BigDecimal across the entire codebase.
```

---

## Module 1: User

**Schema:** `user_schema` | **Tables:** 5

### Tables

#### `users`
One row per human. Phone number is the identity key (E.164 format, unique). A stylist who also books as a customer has ONE users row and TWO user_roles rows.

Key fields: `id` (UUIDv7 PK), `phone` (UK, E.164), `name`, `gender`, `age`, `email`, `profile_photo_url`, `hair_type`, `hair_length`, `default_role`, `is_verified`, `created_at`, `updated_at`

**Why phone not email:** India is phone-first. Every adult Indian has a phone; not every Indian has a reliable email.

#### `user_roles`
Every role a user holds. `role` ENUM: customer / stylist / salon_owner / manager. `salon_id` is NULL for customer roles, set for salon-scoped roles. One user can have multiple rows.

#### `otp_requests`
OTP state during login/signup. **Has NO foreign key to users** — OTPs are sent before a user row exists during signup. `otp_hash` stores bcrypt hash of the 6-digit code, never plaintext. `attempts` tracked; `locked_until` set after 3 failures (10-minute lockout). `expires_at` = 5 minutes from creation.

#### `refresh_tokens`
Long-lived per-device tokens. `token_hash` stored as bcrypt, never plain. `device_fingerprint` used for fraud detection (referral gaming). Individually revocable per device.

#### `onboarding_state`
Transient crash-recovery table. Stores in-progress onboarding so users can resume after app close. **Deleted when onboarding completes** — not a business data table.

### Key Decisions
- OTP table has no FK to users (OTPs sent before user exists)
- Refresh tokens per-device for individual revocation
- `bmp_staff` is completely separate (in admin_schema) — zero shared auth

---

## Module 2: Salon

**Schema:** `salon_schema` | **Tables:** 12

### The Hardest Problem: Availability Model

The slot availability algorithm must intersect:
1. `salon_hours` — is the salon open?
2. `stylist_availability` — is this stylist working/on break/on leave?
3. `stylist_salon.is_available_today` — quick kill switch
4. Existing `booking_service_items` — is the slot already booked?
5. `slot_lock` in Redis — is someone mid-checkout for this slot?

**This is the calendar-truth problem. Getting this wrong kills the product.**

### Tables

#### `salon`
Core salon profile. `location` stored as `GEOGRAPHY(POINT)` — NOT separate lat/lon columns. PostGIS `ST_DWithin` for proximity search (spherical, accurate). `status` ENUM: pending/approved/rejected/suspended/closed. Salons NEVER hard-deleted.

`stylist_assignment_strategy` ENUM: random / highest_rated / least_loaded. `least_loaded` = fewest bookings today (resets daily).

#### `salon_policy`
Cancellation/booking rules. SNAPSHOTTED into every booking at creation — future changes never affect existing bookings. `template` ENUM: strict/standard/flexible. `free_cancel_hours`, `late_grace_minutes`, `require_prepayment` (always true in Phase 1).

#### `salon_hours`
7 rows per salon (one per day). `day_of_week` 0-6. `close_time` means slots must END by this time, not start.

#### `salon_service`
Services offered. `price_paise` BIGINT — integer paise always. `duration_minutes` = shown to customer. `requires_stylist_assignment` BOOLEAN: false = walk-in pool (any available stylist).

#### `stylist`
**The most differentiated table in BMP.** One row per stylist, belongs to the STYLIST not the salon. `user_id` nullable (quick-add by salon before stylist claims profile). `overall_rating` is cross-salon lifetime. `is_top_stylist` = total_reviews >= 50 AND overall_rating >= 4.7.

#### `stylist_salon`
Junction linking stylist to salon for an employment period. `status`: active / alumni. **NEVER deleted — only marked alumni.** `salon_rating` and `salon_review_count` FROZEN when status = alumni. `is_available_today` BOOLEAN — the fastest availability check, flipped in one tap.

#### `stylist_service`
Which services a stylist can perform at a specific salon. `actual_duration_minutes` = real speed (used for slot blocking). NEVER shown to customer — customer sees `salon_service.duration_minutes`. Allows per-stylist pricing via `override_price_paise`.

#### `stylist_availability`
The detailed availability model. `rule_type` ENUM: weekly_template / exception / leave. `slot_type` ENUM: working / break / leave. `blocks_booking` BOOLEAN. A stylist's schedule = weekly_template MINUS exceptions MINUS leave.

#### `salon_combo` and `salon_combo_item`
Bundle services at a discounted price. Cross-category combos allowed (haircut + facial). `allows_addons` = customer can add services on top. `combo_item.requires_specialist` drives multi-stylist assignment. `sequence` determines order of services.

#### `salon_staff` and `staff_invites`
Managers with dashboard access. Invite via WhatsApp deep link (one-time token, 48h expiry). `staff_invites.status`: pending/accepted/declined/expired.

### Key Decisions
- `actual_duration_minutes` hidden from customer to prevent cherry-picking fast stylists
- Stylist identity portable across salons — career record is the stylist's asset
- `is_available_today` is the quick kill switch checked before all other availability logic
- PostGIS GEOGRAPHY (spherical, accurate) not GEOMETRY (flat, inaccurate at scale)
- Salons never hard-deleted — booking history integrity requires them to always exist

---

## Module 3: Booking

**Schema:** `booking_schema` | **Tables:** 8

### State Machine (enforced in code, not just documentation)

```
PENDING    → CONFIRMED    (SYSTEM only — Razorpay webhook. NO other actor.)
CONFIRMED  → ARRIVED      (SALON)
CONFIRMED  → CANCELLED    (CUSTOMER within policy, or SALON on disruption)
CONFIRMED  → NO_SHOW      (SALON after grace period from policy_snapshot)
ARRIVED    → IN_SERVICE   (SALON)
IN_SERVICE → COMPLETED    (SALON — triggers payout queue + review prompt)

Terminal states: COMPLETED, CANCELLED, NO_SHOW
```

Any illegal transition throws `IllegalStateException` in `BookingStatus.java`.

### Tables

#### `booking`
Hub of the module. Created BEFORE payment (so webhook has something to update). Key fields:
- `booking_ref` UK: BMP-2024-08291 (human-readable)
- `final_amount_paise` = what Razorpay charged
- `total_refunded_paise` = running refund total
- `commission_paise` FROZEN at booking creation
- `policy_snapshot` JSONB — FROZEN copy of salon_policy, never changes
- `refund_window_open` BOOLEAN — false after 7 days from scheduled_start
- `confirmed_at` — set by webhook only. This is the legal transaction timestamp.

#### `booking_service_item`
One row per service in the booking. Multi-stylist model lives here. Each item has its own `assigned_stylist_id`, `service_start`, `service_end`. `selection_type` ENUM: specific_stylist / any_available / walk_in_pool.

Snapshot fields (FROZEN at creation): `name_snapshot`, `price_paise_snapshot`, `duration_shown_minutes`, `actual_duration_minutes`.

`item_status` ENUM: active / removed / completed.

#### `slot_lock`
One per stylist per booking during checkout. Redis key = database UUID (same value). `expires_at` = NOW + 5 minutes. Redis TTL auto-releases; DB tracks for analytics. `release_reason` ENUM: booking_confirmed / payment_failed / lock_expired / manual.

#### `booking_events`
**APPEND-ONLY audit timeline.** Never updated, never deleted. 20 event types. Every status change, reminder, refund, modification — logged here with actor and metadata. The ground truth for support and disputes.

#### `booking_disruption`
When a stylist becomes unavailable after confirmation. `notify_customer` BOOLEAN: false if `selection_type = any_available` (silent reassignment). `salon_deadline` = notified_at + 2h. `rejection_count`: after 2 rejections → escalated, salon must raise refund ticket. `customer_acceptance` ENUM: pending / accepted / rejected / not_required.

#### `booking_modification`
Before/after snapshots of every item change. Partial cancellation audit trail.

#### `refund_ticket`
Salon raises → BMP reviews → BMP executes Razorpay refund. **Salon never calls Razorpay directly.** `raised_by_role` ENUM: salon_owner / manager / customer / bmp_system / bmp_admin. `commission_absorbed_by` ENUM: salon / bmp. Minimum ₹10 (1000 paise). 7-day window from visit.

**Refund authority rules:**
- Salon raises ticket = proportional commission returned to salon
- BMP admin override (salon refused valid claim) = BMP absorbs commission, salon keeps payout but pays commission on refunded money (punishment)
- Technical error (bmp_system) = auto-approved, no human review

#### `refund_guard`
One per booking. Hard ceiling = `booking.final_amount_paise`. Prevents over-refunding. Checked and updated atomically on every new refund ticket.

### Key Decisions
- Booking created before payment (webhook has something to attach to)
- Webhook is ONLY payment truth — never the client SDK callback
- Disruption notification ONLY if customer selected specific_stylist. any_available = silent reassignment.
- After 2 rejections → escalated. Salon must raise refund, not propose another stylist.
- Coupon restored ONLY if payment failed pre-confirmation. Post-confirmation cancellation: coupon consumed.
- 7-day refund window. Minimum refund ₹10.

---

## Module 4: Payment

**Schema:** `payment_schema` | **Tables:** 9

### The Cardinal Rule

**BMP NEVER holds customer funds.** Razorpay Route splits at capture: 88% → salon linked account, 12% → BMP account. BMP code never touches the money split.

### Four Money Flows

1. **Customer pays** → Razorpay → webhook → booking confirmed
2. **Nightly payout** → Razorpay Route transfer → salon bank account
3. **Refund** → approved refund_ticket → Razorpay refund API → customer payment method
4. **Commission accounting** → commission_ledger entries

### Tables

#### `payment_order`
One per booking. `razorpay_order_id` UK. `idempotency_key` = booking_id + attempt_number. `razorpay_raw_webhook` JSONB — full payload stored for audit and replay. `payment_captured_at` = legal transaction timestamp (set by webhook only). `commission_paise` and `salon_share_paise` FROZEN at creation.

#### `webhook_event`
**Store raw, return HTTP 200 immediately, process async.** `razorpay_event_id` UK — deduplication at DB level. Second delivery of same webhook fails insert and is silently ignored.

#### `razorpay_linked_account`
Salon's Razorpay sub-merchant account for Route. `verification_status` ENUM: pending / verified / failed_retrying / failed_blocked. KYC fail rules:
- 1-2 failures: accept bookings, hold payouts (`held_pending_kyc`)
- 3 failures: block new bookings (`bookings_blocked = true`)
- 24h cooling period on bank account changes (security against redirect attacks)

#### `payout_queue_item`
Created on `booking.completed` event. `payout_eligible_after` = `booking_completed_at + 7 days` (rolling window). `queue_status` ENUM: pending / held_pending_kyc / eligible / included_in_batch / skipped.

#### `payout_batch`
One per salon per night (11 PM scheduler). Aggregates eligible items. `razorpay_settlement_id` = bank credit proof (T+1). Max 3 auto-retries.

#### `commission_ledger`
Append-only BMP earnings record. Four entry types: commission_earned / commission_refunded / payout_transfer / adjustment. `amount_paise` signed (positive = credit, negative = debit).

#### `refund_execution`
Created when payment module receives `refund.approved` event. Calls Razorpay refund API. Stores `razorpay_refund_id` (rfnd_XXXXXXX) — appears on customer's bank statement.

#### `bmp_account`
Two accounts: `operations` (receives commission) and `reserve` (20% weekly sweep for chargebacks). `total_refunds_absorbed_paise` tracks cost of admin override refunds.

#### `saas_subscription` and `saas_invoice`
SaaS plan per salon. Collected via Razorpay Payment Links (not Route). `gst_paise` = 18% GST on SaaS fee (required for B2B invoices in India). Plans: pilot (free 90 days) / standard (₹1,500/month) / premium (₹3,000/month).

### Key Decisions
- 7-day rolling payout window (not percentage holdback)
- Commission rate frozen at payment_order creation
- Two BMP Razorpay accounts: operations + reserve
- KYC fail = hold payouts not block bookings (until 3rd failure)
- SaaS fee via Payment Links, commission via Route (different mechanisms)
- Salon coupon = commission on PRE-DISCOUNT subtotal (BMP unaffected by salon promotions)

---

## Module 5: Review

**Schema:** `review_schema` (PostgreSQL) + `community_db` (MongoDB) | **11 total**

### PostgreSQL Tables

#### `review`
`booking_id` NOT NULL UK — **no booking, no review**. This is the anti-fake-review mechanism. `salon_rating` (1-5) mandatory. `stylist_rating` (1-5) mandatory if stylist assigned. `edit_locked_at` = created_at + 7 days — permanently locked after.

`community_post_id` VARCHAR(36) = MongoDB ObjectId — the bridge key. `needs_remoderation` BOOLEAN — set on text edits (triggers re-moderation queue).

#### `review_edit_history`
Every version preserved. Version 1 = original. Never overwrites — inserts new row. `salon_response_hidden` BOOLEAN — salon's "Thanks for 5 stars!" hidden when review edited to 2 stars.

#### `review_prompt`
Scheduled 30 minutes after `booking.completed`. WhatsApp first, push as fallback. `expires_at` = send_after + 7 days.

#### `salon_rating_snapshot` and `stylist_rating_snapshot`
Pre-computed aggregates. **Never computed live on queries.** Updated by review module after each approved review. `overall_rating` = equal weight — old reviews count same as new ones. Recency affects display order only.

`reviews_last_30_days` and `rating_last_30_days` shown as trend indicators but NOT used in rating calculation.

`qualifies_top_stylist` = total_reviews >= 50 AND overall_rating >= 4.7.

Stylist snapshot has both `salon_rating` (per-salon, FROZEN on departure) and `overall_rating` (cross-salon lifetime).

#### `salon_response`
Owner's public reply to a review. Editable within 24h. Hidden by BMP moderation if abusive.

### MongoDB Collections

#### `community_posts`
Feed posts. `salon_location` GeoJSON Point with `2dsphere` index — powers the Nearby feed. `hashtags` array with index — powers hashtag search. `is_visible` BOOLEAN — false if removed/flagged.

Two post types: `customer_review_post` (linked to review) and `stylist_portfolio_post` (stylist-initiated, no review required).

Denormalised fields: `likes_count`, `comments_count`, `salon_name`, `stylist_name`, `is_top_stylist`.

#### `post_likes`
Unique compound index on `(post_id, user_id)` — prevents double-likes at DB level.

#### `post_comments`
`parent_comment_id` for threading. Maximum 2 levels deep.

#### `stylist_follows`
Follow graph. `notifications_on` BOOLEAN — push notification when followed stylist posts.

#### `post_engagement_score`
**Separate collection** (not embedded in community_posts — prevents write contention on hot posts).

```
trending_score = (likes × 1 + comments × 3 + shares × 5) ÷ (hours_since_posted + 2) ^ 1.5
is_eligible_trending = post_age > 1h AND post_age < 7 days
```

Comments × 3 because they signal intent. Shares × 5 because they reach 50-200 new people. No geo filter on Trending — city-wide and national.

### PostgreSQL ↔ MongoDB Bridge
- `review.community_post_id` → `community_posts.review_id` (string)
- Moderation synced via outbox event (one action, both DBs updated)
- No cross-DB foreign key — enforced by application

### Key Decisions
- booking_id required (NOT NULL) — no fake reviews possible
- Equal weight for all reviews regardless of age
- 7-day edit window, all versions preserved in history
- Re-moderation triggered on text edits (not rating-only edits)
- Salon response hidden when review text changes significantly
- Trending has no geo filter — engagement-based, city/national scope

---

## Module 6: Rewards

**Schema:** `rewards_schema` | **Tables:** 10

### The Wallet Rule

**Wallet is NON-WITHDRAWABLE.** Customers cannot transfer to bank. This keeps BMP outside RBI's Prepaid Payment Instrument (PPI) licensing requirement. Stated clearly in UI.

### Tables

#### `coupon`
8 coupon types: welcome / referral / loyalty / off_peak / festival / birthday / win_back / salon_specific.

**6 validation rules run in sequence (all must pass):**
1. Active and within date window
2. Applicable to this salon (salon_id matches or null = platform-wide)
3. Per-user usage limit not exceeded
4. Booking subtotal meets minimum spend
5. Total usage cap not exceeded
6. First-booking check (if required)

`commission_base` ENUM: pre_discount / post_discount.
- **salon_specific ALWAYS = pre_discount** (BMP charges commission on pre-discount subtotal — salon bears both discount and full commission)
- **Platform coupons = post_discount** (BMP absorbs commission reduction)

`allows_wallet_stacking` BOOLEAN — some coupons exclude wallet usage.

#### `coupon_usage`
`was_refunded` BOOLEAN — **ONLY true if payment failed before confirmation**. Post-confirmation cancellation: coupon consumed permanently.

#### `wallet`
`balance_paise` BIGINT — never negative. `is_frozen` BOOLEAN — fraud investigation.

#### `wallet_transaction`
Append-only ledger. `balance_after_paise` snapshot on every transaction. 9 transaction types. NEVER modified or deleted.

#### `referral` and `referral_code`
`referral_code` = persistent shareable code per user (DARSHAN-X7K format). `referral` = one row per referee. Rewards issued ONLY after referee's FIRST COMPLETED VISIT (not signup). `expires_at` = referred_at + 90 days. `fraud_reason` ENUM: same_device / same_ip / self_referral / already_referred.

Referrer gets ₹150, referee gets ₹100 — amounts FROZEN at referral creation.

#### `checkout_discount`
FROZEN at checkout confirmation. `commission_base_paise` locked (pre or post discount). `final_charge_paise` = what Razorpay charges. Never recalculated. `coupon_restore_policy` = restore_on_pre_confirm_only.

#### `win_back_job_log`
Tracks win-back sends. **60 days since last booking ANYWHERE on BMP** (platform-level churn, not per-salon). `sent_at` used for 90-day dedup check. `reactivated` BOOLEAN — booked within 30 days = conversion.

#### `loyalty_account` and `loyalty_transaction`
Schema-ready for Phase 2 — NOT active in Phase 1. Points system: ₹1 spent = 1 point, 500 points = ₹50 wallet credit. Tiers: bronze/silver/gold/platinum.

### Key Decisions
- Wallet non-withdrawable (RBI PPI compliance)
- 6 validation rules in sequence, specific error per failure
- Salon coupon: BMP commission on PRE-DISCOUNT subtotal always
- Coupon not restored after payment confirmation — only on payment failure
- Win-back = 60 days anywhere on platform (not per-salon)
- Referral reward only on first completed visit (not signup — prevents fake account abuse)
- Loyalty schema exists but feature flag = off for Phase 1

---

## Locked Product Decisions

These were decided during the design session and must NOT be re-debated:

| Decision | Answer |
|---|---|
| Stylist selection model | Per-service at checkout. Same stylist selectable across combo services. |
| Disruption notification | Only for specific_stylist selection. any_available = silent reassignment. |
| Disruption rejection limit | After 2 rejections → escalated, salon raises refund ticket |
| Partial cancellation pricing | Salon decides refund amount. BMP executes. No BMP approval needed for salon-discretion refunds. |
| Refund authority | Salon raises ticket, BMP reviews and executes Razorpay call. Salon never calls Razorpay directly. |
| Commission on salon coupons | Pre-discount subtotal — BMP always charges full commission regardless of salon promotions |
| Win-back trigger | 60 days since last booking anywhere on BMP |
| Coupon after cancellation | Not restored. Only restored if payment failed before confirmation. |
| Payout window | 7-day rolling (not percentage holdback) |
| KYC fail | Accept bookings, hold payouts. Block bookings only after 3 failures. |
| Commission split | Instant Razorpay Route at capture (not periodic) |
| Review editing | Editable within 7 days. All versions preserved. |
| Rating calculation | Equal weight forever. Recency affects display order only. |
| Trending tab | No geo filter. Engagement score with time decay. |
| Minimum refund | ₹10 (1000 paise) |
| Refund window | 7 days from visit |
| Admin override commission | BMP absorbs. Salon keeps payout but pays commission on refunded money. |
| Least-loaded calculation | Bookings per day (resets daily) |
| Walk-in pool services | `requires_stylist_assignment = false` — any stylist can do it |
| Stylist picker filter | Shows only stylists with active `stylist_service` row for that service |

---

## What to Build Next

**Priority order:**

1. **Admin module schema** — bmp_staff, support_ticket, support_message, audit_log (REVOKE DELETE at DB level)
2. **Notification module schema** — stateless processor, notification_log
3. **Availability model implementation** — HIGHEST TECHNICAL PRIORITY. Design against 3 real pilot salons before coding. The slot generation algorithm must intersect salon_hours + stylist_availability rules + existing bookings + slot_locks.
4. **LLD (API contracts)** — scope to monolith, availability model as first section
5. **Confirm Razorpay Route structure** with Razorpay directly before payment code
6. **Phase 0 kill criteria** — all 3 founders must agree before any interviews
7. **Push this CONTEXT.md to GitHub** as the team shared brain

---

## Documents and Files

All documents are in the `docs/` folder in this repository.

### Schema Diagrams (`docs/schema-diagrams/`)
| File | Contents |
|---|---|
| `00_overview.png` | All 6 modules at a glance |
| `01_user_module.png` | User module — 5 tables |
| `02_salon_module.png` | Salon module — 12 tables |
| `03_booking_module.png` | Booking module — 8 tables |
| `04_payment_module.png` | Payment module — 9 tables |
| `05_review_module.png` | Review module — PostgreSQL + MongoDB |
| `06_rewards_module.png` | Rewards module — 10 tables |

### Design Documents (`docs/design-documents/`)
| File | Contents |
|---|---|
| `BMP_Core_Module_Schema.docx` | **MASTER SCHEMA DOC** — 3,741 paragraphs, every field with Why explanation |
| `BMP_Database_Schema.docx` | Earlier conceptual schema (29 tables) |
| `BMP_HLD.docx` | High-level system architecture |
| `BMP_BeMyProfessional_Masterplan.docx` | Full product masterplan |
| `BMP_Complete_Functional_Flows.docx` | All functional flows end-to-end |
| `BMP_UX_Flow_Diagrams.docx` | UX flow diagrams |

### UX Screens (`docs/ux-screens/`)
60+ screens across 4 platforms. Design system: Navy #0C447C, Blue #185FA5, Active #378ADD, LightBlue #E6F1FB, Green #27AE60, Red #E24B4A, Amber #F39C12, Star #F4A623. Font: Arial.

| File | Covers |
|---|---|
| `BMP_UI_Screens.docx` | Customer app screens v1 |
| `BMP_UI_Screens_v2.docx` | Customer app screens v2 |
| `BMP_Auth_Flow.docx` | Authentication screens A1-A10 |
| `BMP_Booking_Payment_Screens.docx` | Booking B1-B7, Payment P1-P5 |
| `BMP_Community_Screens.docx` | Community C1-C4 |
| `BMP_MyAccount_Screens.docx` | My Account M1-M6 |
| `BMP_SalonDashboard_Screens.docx` | Salon Owner web SO1-SO6 |
| `BMP_SalonMobile_Screens.docx` | Salon Owner Android SM1-SM8 |
| `BMP_AdminPanel_Screens.docx` | Admin panel AP1-AP8 |

### Source Code
The Maven multi-module skeleton is in the root of this repository. Key files:
- `bmp-common/src/main/java/com/bmp/common/money/Money.java` — integer paise implementation
- `bmp-common/src/main/java/com/bmp/common/ids/UuidV7.java` — UUIDv7 generation
- `bmp-booking/src/main/java/com/bmp/booking/api/BookingStatus.java` — state machine with actor enforcement
- `bmp-common/src/main/java/com/bmp/common/outbox/OutboxPublisher.java` — outbox pattern
- `bmp-notification/src/main/java/com/bmp/notification/internal/OutboxProcessor.java` — relay worker
- `bmp-app/src/test/java/com/bmp/ModularityTests.java` — ArchUnit boundary tests
- `bmp-app/src/main/resources/db/migration/V001__baseline_schemas_and_outbox.sql` — Flyway baseline

---

## Full Session Log — Every Chat Turn Summarised

This section is the complete history of every decision made across every AI session. Read this to understand WHY things are designed the way they are.

---

### Session 1 — May 25, 2026 (Previous Session — UX/UI Design)

**What was done:** Complete UX/UI design for all 4 platforms.

**Delivered:**
- Customer app: 60+ screens across Auth (A1-A10), Discovery (D1-D6), Booking (B1-B7), Payment (P1-P5), My Account (M1-M6), Community (C1-C4)
- Salon Owner web dashboard: SO1-SO6
- Salon Owner Android: SM1-SM8 (including SM7 notifications feed and SM8 no-show confirmation)
- Admin Panel: AP1-AP8

**Design system locked:** Navy #0C447C, Blue #185FA5, Active #378ADD, LightBlue #E6F1FB, Green #27AE60, Red #E24B4A, Amber #F39C12, Star #F4A623. Font: Arial.

---

### Session 2 — June 25, 2026 → July 8, 2026 (Schema + Architecture Design)

#### Turn 1 — Database Schema Direction
**Darshan chose:** Database schema first (conceptual — entity tables + relationships). Started with initial 29-table conceptual schema for 8 microservices.

#### Turn 2 — HLD Built
Produced full High-Level Design document including:
- 8-microservice architecture diagram
- Booking/payment sequence diagram (Razorpay webhook flow)
- Deployment: Fly.io + Supabase + Vercel + Upstash
- Security architecture: 4 defense layers, admin auth completely separate

#### Turn 3 — GTM Document Uploaded and Reviewed
**Darshan uploaded:** `BMP_Honest_Assessment_and_GTM_Plan.docx`

Key flaws identified in the original design:
- **T1:** Microservices too early for 3-person team → pivot to modular monolith
- **T2:** Kafka overkill → replace with Postgres outbox
- **T3:** Cross-service integrity by promise → real FKs in monolith
- **T4 (most critical):** Availability model completely missing from schema
- **T5:** Cost base too heavy → one VM + managed Postgres for Phase 1
- **T6:** 4 platforms pre-PMF → Salon web first, customer PWA + WhatsApp link

Business flaws:
- **F1:** Calendar-truth problem is company-defining → deeper integration, fewer salons, manual Phase 1
- **F3:** Unit economics only work at premium tickets (₹800+)
- **F8 (most critical):** BMP must never hold customer funds → Razorpay Route is mandatory

**Verdict:** GTM document wins on business model and execution sequencing. Original sessions win on UX/UI depth (still valid as build targets).

#### Turn 4 — Comparison Analysis
Full side-by-side comparison: original design vs GTM document across business model, revenue, architecture, UX scope, availability model, compliance. GTM document wins most categories.

#### Turn 5 — Context Handoff Requested
Darshan asked for a summary document any AI app can understand. Produced `BMP_Master_Context_Handoff.md` covering all 12 areas including what AI agents must never propose.

#### Turn 6 — Architecture Decision: Modular Monolith
**Darshan said:** "I am thinking to go for multi module microservice approach"
**Claude clarified:** Two interpretations — full microservices (wrong for 3 people) vs multi-module monolith (correct)
**Darshan chose:** Multi-module monolith (Reading 2)

Architecture locked:
```
bmp-platform/ (parent pom)
bmp-common, bmp-user, bmp-salon, bmp-booking, bmp-payment,
bmp-review, bmp-rewards, bmp-admin, bmp-notification, bmp-app
```

#### Turn 7 — Kafka + MongoDB Decision
**Darshan said:** "Include Kafka also and then we need restructure schema and all i need use mongo db where we can use"
**Decision on Kafka:** Add it back for enterprise pattern even if throughput doesn't demand it yet → Upstash Kafka
**Decision on MongoDB:** Community feed + before/after photos, analytics — NOT as general-purpose replacement for PostgreSQL

#### Turn 8 — Skeleton Code Built
Maven multi-module skeleton created with:
- `Money.java` — integer paise with rounding
- `UuidV7.java` — time-ordered UUIDs
- `BookingStatus.java` — state machine with actor enforcement
- `OutboxPublisher.java` and `OutboxProcessor.java`
- `ModularityTests.java` — ArchUnit boundary enforcement

**File:** `bmp-platform-skeleton.zip`

#### Turn 9 — Module-by-Module Schema Design Begins
**Darshan:** "Can we start module wise schema diagrams. In details. Every in detail"
**Decision:** Go one module at a time, discuss and lock before moving on.

---

### Session 3 — July 13, 2026 (This Session — Schema Design Completion)

#### Turn 1 — User Module
Designed all 5 tables. Key decisions locked:
- Phone as identity (not email)
- OTP table has no FK to users
- bcrypt for OTP hash
- Refresh tokens per-device

**Darshan asked:** "Why User Module explain in brief" — Claude explained the design rationale.

#### Turn 2 — Salon Module
Designed all 12 tables. Key decisions:
- PostGIS GEOGRAPHY for location (not lat/lon columns)
- Stylist portable identity — never deleted, only alumni
- `actual_duration_minutes` hidden from customer
- `is_available_today` as quick kill switch
- Cross-category combos allowed

**Darshan asked about:** Booking window, stylist specialisation matching, stylist selection by customer, combo services — all answered and locked.

#### Turn 3 — Team Collaboration
**Darshan asked:** "I have friends using different laptop with different Claude account. We are 3 founders. How can we work together?"
**Solution:** CONTEXT.md in the shared GitHub repo `bmp-platform`. Each founder loads it at the start of every AI session. Tool-agnostic — works with Claude, Anti-Gravity, or any AI tool.

#### Turn 4 — Booking Module Questions
**Darshan asked about:** Combo cross-category, customer+combo addons, stylist duration visibility, booking allocation

**Answers locked:**
- Cross-category combos: YES
- Combo + addon in same booking: YES
- Show actual_duration to customer: NO (show salon standard only)

#### Turn 5 — Booking Module Detailed Schema Design
Full booking module schema with all 8 tables designed. Per-service stylist selection. Multi-stylist bookings. selection_type ENUM introduced.

#### Turn 6 — Selection Type and Disruption Rules
**Darshan clarified:** Notify customer immediately even for reassignment (if they picked specific stylist). If any_available: no notification needed.

Locked:
- Show only stylists who can do that specific service (filtered by stylist_service)
- After 2 rejections → salon raises refund (no auto-cancel)
- least_loaded = bookings per day
- Services can exist without stylist assignment (walk-in pool)

#### Turn 7 — Refund Authority
**Darshan:** "Salon can raise refund at any time they have authority"
Designed dedicated `salon_refund` table. Then Darshan clarified: "They can raise refund ticket ultimately we will do it at last" — ticket-first model.

Final model: Salon raises `refund_ticket` → BMP reviews → BMP executes Razorpay call.

Three questions answered:
- Minimum refund: YES keep ₹10 minimum
- Time limit: 7 days from visit
- Admin override commission: Salon absorbs if they raised it. BMP absorbs if admin overrides.

#### Turn 8 — Complete Booking Module Schema
All 8 tables finalised. State machine documented. Outbox events specified.

#### Turn 9 — Payment Module
Designed all 9 tables. Four money flows specified. Three product questions answered:
- Payout window: 7-day rolling (not percentage holdback) — Darshan agreed after analysis
- KYC fail: accept bookings, hold payouts (block after 3 failures) — same analysis
- Commission split: instant Razorpay Route — Darshan asked for product owner analysis comparing competitors

**Analysis delivered:** Compared Razorpay Route vs periodic settlement. Instant Route wins on cash flow, RBI compliance, collection risk, accounting complexity. Darshan agreed.

Added: Two BMP accounts (operations + reserve), 20% weekly sweep, SaaS subscription tables.

#### Turn 10 — Review Module
Designed 6 PostgreSQL + 5 MongoDB tables.

Three questions answered:
- Review editing: YES within 7 days
- Rating weight: Equal for all reviews regardless of age
- Trending without geo: YES — engagement-based, city-wide

Added: `review_edit_history`, `post_engagement_score`, trending algorithm.

#### Turn 11 — Rewards Module
Designed all 10 tables. Three questions answered:
- Salon coupon commission: Pre-discount subtotal (BMP unaffected by salon promotions)
- Win-back scope: 60 days anywhere on BMP platform
- Coupon after partial cancel: Not restored (coupon consumed on confirmation)

Added: `win_back_job_log`, `commission_base` field on coupon.

#### Turn 12 — Master Schema Document
**Darshan:** "Can get me well very clear very in details module with schema diagrams. Explanations of schema in very details of each and every table in document."

Produced `BMP_Core_Module_Schema.docx`:
- 3,741 paragraphs
- 131KB
- Landscape A4
- 5 columns per field: name, type, key type, constraint/rule, WHY this design
- Appendix A: Cross-module relationships
- Appendix B: Kafka event flows

#### Turn 13 — Schema Diagrams
**Darshan:** "Get me all schema diagrams so I can download them"

Produced 7 PNG files at 2× resolution:
- `00_overview.png` — all modules
- `01_user_module.png` through `06_rewards_module.png`

#### Turn 14 — Complete Project Structure
**Darshan:** "Create complete project structure and add this .md file to it also add all schema and docs inside this as one folder"

Created `bmp-platform/` with:
- Full Maven multi-module skeleton code
- `docs/schema-diagrams/` — 7 PNG files
- `docs/design-documents/` — 6 DOCX files
- `docs/ux-screens/` — 9 DOCX files
- `CONTEXT.md` — this file

#### Turn 15 — This File (CONTEXT.md)
**Darshan:** "Add agent instructions about this chat full chat in .md folder also so other friends who are using other AI agent then feed and continue and they also adding if any extra things in that .md folder so we can also get to know"

This is the file you are reading now.

---

## How to Add to This File

When you complete a session, add a new turn entry under the current session in the Session Log. Format:

```markdown
#### Turn N — [Brief topic title]
**Darshan asked/decided:** [What was asked or decided]
**Answer/Decision:** [What was locked]
**Tables/files affected:** [What changed]
```

If you are making a schema change, also update the relevant module section above.

**Rules for this file:**
- Never delete existing content — only add
- Never change a locked decision without noting team discussion date
- Keep the Schema Rules section exactly as written — these are enforced in code
- When adding new tables, follow the format: table name → key fields → key design decisions

---

*Last updated: July 13, 2026 — Session 3 complete. All 6 core modules schema-locked. Starting Admin module next.*
