# BMP (Be My Professional) — Complete Project Context

> **For AI Agents:** Read this entire file before responding to anything. This is the single source of truth for the BMP project. Every architectural decision, every schema rule, every product deci[...]
>
> **For Team Members:** Update the [Session Log](#session-log) section when you complete a session. Add your decisions to [Locked Decisions](#locked-decisions). Never change locked decisions withou[...]

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
14. [Module 7: Admin — SCHEMA DONE (V008), added Session 4]
15. [Module 8: Notification — SCHEMA DONE (V009), added Session 4]
16. [Locked Product Decisions](#locked-product-decisions)
17. [What to Build Next](#what-to-build-next)
18. [Documents and Files](#documents-and-files)
19. [Full Session Log — Every Chat Turn Summarised](#full-session-log--every-chat-turn-summarised)
20. [How to Add to This File](#how-to-add-to-this-file)

---

## What is BMP

**Be My Professional** is a premium salon booking platform targeting Bengaluru, India. Customers discover, book, and pre-pay for salon services. Salons manage their calendar, stylists, and payouts[...]

### The Problem BMP Solves

Every Indian salon marketplace before BMP (Fabogo, Vyomo, Bulbul, Zoylee) died because of the **calendar-truth problem**: salons listed availability they didn't honour, customers showed up for app[...]

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

**Phase: Microservices Split Complete (Session 5) → Phase 1 CRUD In Progress (Session 6 — Dev Achyuth) → Session 7 (BMP-6 & BMP-30 Complete) → Phase 2 Availability Algorithm Done (Session 9-10) → Session 12: full local build+run actually verified working end-to-end for the first time, ~20 real bugs found & fixed in the process**

⚠️ **As of Session 12, none of that session's fixes are committed yet** — they're all
still local working-tree changes on `feature/booking-availability-wiring` (`git status`
shows ~63 modified/untracked files). If you're an AI agent picking this project up,
run `git status`/`git diff` before assuming the state described in the Session 12 log
entry is actually on the branch you're looking at — someone needs to commit and push
it first. See the Session 12 log entry for the full list and **`RUN_LOCALLY.md`** (repo
root) for the actual step-by-step build/run guide.

⚠️ **Session 5 reversed the "modular monolith, not microservices" LOCKED decision below** (see
Technology Stack table and Session 5 log entry). This was a **Darshan-only decision**, made and
executed in a single Cowork session with Shivam and Achyuth not present — same caveat as the
Session 4 availability-model work. **Flagged for both of you to review before this is treated
as final.** Nothing here is unrecoverable (the old bmp-app monolith entry point still exists,
see `bmp-app/RETIRED.md`), but going forward all 3 of you need to agree this is the direction.

### Status table — refreshed Session 25

| Area | Status |
|---|---|
| Product strategy and GTM | ✅ LOCKED |
| UX/UI design (60+ screens) | ✅ COMPLETE |
| All core module schemas | ✅ COMPLETE — V001(outbox) onward per service, 57+ JPA entities |
| Architecture | ⚠️ CHANGED Session 5 — modular monolith → **microservices** (Darshan-only, NOT ratified) |
| Service registry (Eureka) + API Gateway | ✅ DONE |
| bmp-auth (OTP/JWT, 4 roles, Google OAuth) | ✅ DONE |
| Phase 1 CRUD — all 9 modules | ✅ DONE |
| Availability algorithm (freeSlots / blockWalkIn) | ✅ DONE (Sessions 9–10), wired into booking creation |
| Salon staff management (owner adds/removes managers, invites stylists) | ✅ DONE (Sessions 15, 17) |
| Stylist availability — write side | ✅ DONE (Session 18) |
| Walk-ins from the manager desk | ✅ DONE (Session 19) |
| **bmp-admin — staff auth, TOTP 2FA, RBAC, audit** | ✅ DONE (Session 20) |
| **bmp-admin — moderation, data requests, settings, reports, refunds** | ✅ DONE (Sessions 21, 23) |
| **Coupons & referrals with audience targeting** | ✅ DONE (Session 22) |
| **Authorization pass — bmp-booking, bmp-rewards** | ✅ DONE (Sessions 21–22) — both were previously **fully unauthenticated** |
| Authorization pass — bmp-payment, bmp-review, bmp-notification | 🔜 NOT DONE |
| **Payments (Razorpay)** | ❌ NOT STARTED — blocks refund payouts, manager check-in, referral rewards |
| Reviews UI, owner dashboard, reschedule | 🔜 NOT STARTED |
| Razorpay Route confirmation | ⏳ PENDING (confirm directly with Razorpay) |
| **Automated tests** | ❌ **NONE, anywhere, in any of the three repos** |

**The two honest headlines:** no payments means no money has ever moved, so several features
(refund payouts, check-in, referral rewards) are structurally unreachable rather than buggy.
And there are no tests at all — every regression so far has been caught by a person running the
thing.

---

## Technology Stack — LOCKED (⚠️ Architecture row overridden Session 5 — see below)

| Layer | Choice | Why |
|---|---|---|
| Language | Java 21 | LTS, virtual threads, records |
| Framework | Spring Boot 3.4.1 + Spring Cloud 2024.0.0 | Netflix Eureka + Gateway + OpenFeign for microservices |
| ~~Architecture~~ Architecture | ~~Multi-module Maven monolith~~ **Independent Spring Boot microservices, one per module, registered with Eureka, routed via Spring Cloud Gateway** | ⚠️ CHANGED Session 5 (Darshan-only, not ratified) |
| Primary DB | PostgreSQL 16 + PostGIS 3.4 | Relational integrity + geospatial proximity search. UNCHANGED: still one physical Postgres instance, schema-per-service — the split is at the service deploy boundary |
| Document DB | MongoDB 7 | Community feed only — not general purpose |
| Cache / Locks | Redis | Slot locks during checkout only |
| Async events | Postgres Outbox + relay worker (`bmp-common`, shared across all services) | Replaces Kafka — simpler, same guarantees. Now the ONLY sanctioned way for services to react to each other |
| Inter-service sync calls | Spring Cloud OpenFeign | Used where a synchronous cross-service read is unavoidable (e.g. bmp-auth calling bmp-user). Marked with TODO comments everywhere a Phase 1 CRU endpoint should become async in Phase 3 |
| Service discovery | Netflix Eureka (`eureka-server`, port 8761) | All services register here; the gateway and Feign clients resolve `lb://bmp-*-service` through it. |
| API Gateway | Spring Cloud Gateway (`api-gateway`, port 8080) | Single external entry point; routes by path prefix to each service. |
| Payments | Razorpay Route | BMP never holds funds — splits at capture |
| File storage | Cloudflare R2 | Before/after photos, salon photos, stylist photos |
| WhatsApp | MSG91 | OTP + booking notifications + review prompts |
| Boundary tests | ArchUnit + Spring Modulith verify() | ⚠️ Spring Modulith annotations were REMOVED from all `package-info.java` files in Session 5 (each module is now its own deployable, not part of a monolith) |

**DO NOT SUGGEST:** Kafka (replaced by outbox), BigDecimal for money (integer paise only), separate physical databases per module (still one Postgres, multiple schemas — only the deploy unit changed)

**Session 5 port table (standard-range, sequential):**

| Service | Port |
|---|---|
| eureka-server | 8761 |
| api-gateway | 8080 |
| bmp-auth-service | 8081 |
| bmp-user-service | 8082 |
| bmp-salon-service | 8083 |
| bmp-booking-service | 8084 |
| bmp-payment-service | 8085 |
| bmp-review-service | 8086 |
| bmp-rewards-service | 8087 |
| bmp-admin-service | 8088 |
| bmp-notification-service | 8089 |

---

## What to Build Next

**Status as of Session 7:** All 8 module schemas exist as Flyway migrations (V002-V009) with matching JPA entities (57 total). **Notification module Phase 1 CRUD now complete.** Work sequencing:

**Phase 1 — CRUD & Basic Ops (in progress, 6/8 modules done):**
1. ✅ Admin module CRUD (Session 6)
2. ✅ Notification module CRUD (Session 7 — BMP-6 & BMP-30)
3. 🔜 User module CRUD (entities exist, need repository/service/controller)
4. 🔜 Salon module CRUD (entities exist, need repository/service/controller)
5. 🔜 Booking module CRUD (entities exist, need repository/service/controller)
6. 🔜 Payment module CRUD (entities exist, need repository/service/controller)
7. 🔜 Review module CRUD (entities exist, need repository/service/controller)
8. 🔜 Rewards module CRUD (entities exist, need repository/service/controller)

**Phase 2 — Availability Logic (after Phase 1 complete):**
- Availability model algorithm — `AvailabilityApi.freeSlots()`, `blockWalkIn()` — this is the next real engineering priority

**Phase 3 — Inter-Service, Auth & Integrations (deliberately deferred):**
- Real Razorpay Route integration
- Real MSG91 WhatsApp + FCM push integration
- Cross-module events via outbox

---

## Full Session Log — Every Chat Turn Summarised

### Session 7 — July 20, 2026 (This Session — Notification Module CRUD)

**Shivam's Request:** "start implementing and before that please refer the context.md and after making any changes please do update thanks"

**What was done:**
- ✅ **BMP-6:** Designed `notification_log` table (V009) — created NotificationLog JPA entity with all fields per CONTEXT.md Module 8
  - Channels: WHATSAPP, SMS, PUSH, EMAIL
  - Status: QUEUED → SENT → DELIVERED/FAILED
  - Outbox pattern: traces `outbox_entry_id` back to common_schema.outbox
  - UUIDv7 primary key, Instant timestamps, JSONB payload

- ✅ **BMP-30:** Built CRUD REST API for notification_log (bmp-notification-service, port 8089)
  - **NotificationLogRepository:** Spring Data JPA with specialized queries (by recipient, by channel/status, pending, stalled, analytics)
  - **NotificationLogService:** Business logic (create, getById, markSent/Delivered/Failed, getStats)
  - **NotificationLogController:** 8 REST endpoints
    - GET /api/notifications/{id} — retrieve single
    - GET /api/notifications?recipientId=X — list paginated
    - GET /api/notifications/pending — get undelivered (QUEUED or SENT)
    - GET /api/notifications/stats — stats by status
    - POST /api/notifications — create new log entry
    - PUT /api/notifications/{id}/sent — mark sent
    - PUT /api/notifications/{id}/delivered — mark delivered
    - PUT /api/notifications/{id}/failed — mark failed

**Build Errors Found & Fixed:**
1. NotificationLogController line 38: `log.getLogger().info()` → removed invalid method
2. NotificationLogService lines 55,116,132,148: `log()` helper calling undefined `logger` → replaced with direct `log.info()` and `log.warn()`

**Commits Pushed:**
- `70bd1490...` — Initial 4 files (had errors)
- `cf7541f5...` — Fix NotificationLogController
- `9fb621ef...` — Fix NotificationLogService (FINAL, ERROR-FREE)

**Architecture Compliance:**
- ✅ Integer paise (Money type support via bmp-common)
- ✅ UUIDv7 via `UuidV7.generate()` for IDs
- ✅ Outbox pattern: traces triggering event
- ✅ Microservices: bmp-notification-service (port 8089), internal package isolation
- ✅ Phase 1: CRUD only, Phase 3 for real MSG91/FCM providers
- ✅ Stateless module: logs send attempts, real state lives in owning modules

**Locked Decisions Maintained:**
- No cross-module imports from `internal` package
- No BigDecimal anywhere
- SLF4J via @Slf4j annotation (NOT custom logger wrapper)
- Transactional boundaries per business operation

---

### Session 8 (Darshan/Cowork) — Auth+OTP, Kafka, Swagger, Actuator/Config Server/Monitoring, and reconciliation with the Phase 1 CRUD branch

**Darshan's requests this session:** full role-based auth (customer/salon-owner/manager/
stylist signup+login, dual-channel OTP email+phone, Google sign-in), switch the outbox
relay from in-process to Kafka, Swagger/OpenAPI on every service, then Actuator + a
GitHub-backed Config Server + Spring Cloud Bus + a Spring Boot Admin monitoring service —
followed by `git pull` once Shivam/Achyuth's Phase 1 CRUD branch had merged.

**Auth/OTP/Kafka (bmp-auth, bmp-notification, bmp-common):**
- Role-based signup/login for customer, salon owner, manager, stylist, all through
  bmp-auth's OTP flow (request/verify), reusing existing-but-unwired schema
  (`salon_staff`, `staff_invites`, `stylist_salon`) instead of duplicating it.
- OTP sent via both email and phone for every role. Customer signup can optionally use
  Google Sign-In (server-side ID token verification against Google's `tokeninfo`
  endpoint) — since Google gives no phone number and `users.phone` is NOT NULL (locked),
  a Google-authenticated user without a phone gets `linked=false` back and completes a
  normal phone-OTP signup, passing `googleSubject` through to link the two.
- Email + SMS/OTP delivery is console-log-only for now (`LoggingEmailSender`,
  `LoggingSmsSender`) — real SMTP/SMS wiring exists (`SmtpEmailSender`) but isn't active,
  by explicit request, for easy local testing.
- Kafka (KRaft mode, single-node, `apache/kafka:3.8.0`) replaces the in-process outbox
  relay — this REVERSES the "Kafka replaced by outbox" locked decision in the Technology
  Stack table above (still shown struck through, not deleted, for the historical record).
  The write side is unchanged (`OutboxPublisher` still writes transactionally); only the
  RELAY changed, from in-process consumer invocation to `OutboxKafkaRelay` publishing to
  `bmp.events`. `NotificationDispatcher` (`@KafkaListener`) is the consumer — the first
  real "queued → sent/failed" transition `notification_log.status` has ever had.
- Dual-credential security model, shared via `com.bmp.common.security`: end-user JWT
  bearer tokens (role + salonId claims) via `JwtAuthFilter`, and a static
  `X-Internal-Service-Key` header (`ROLE_SERVICE`) for service-to-service calls — used
  consistently by bmp-auth's Feign clients, bmp-salon's internal endpoints, and (see
  below) bmp-monitoring's actuator polling.

**Swagger/OpenAPI:** every business/auth service got `springdoc-openapi-starter-webmvc-ui`
plus its own `OpenApiConfig` with a real, service-specific description (not boilerplate),
and every controller endpoint got `@Tag`/`@Operation` descriptions.

**Actuator, Config Server, Cloud Bus, Monitoring:**
- Actuator on all 11 services. bmp-auth (the one service with a real authorization pass)
  only exposes health/info publicly — everything else (env, beans, refresh, busrefresh,
  threaddump, heapdump) needs a credential. The other 8 business services expose the
  same endpoint set but aren't path-gated yet, same as their existing "no authorization
  pass" status.
- **bmp-config-server** (new, port 8888): Spring Cloud Config Server reading
  `config-repo/` from this same GitHub repo. `/monitor` + `spring-cloud-config-monitor`
  is the GitHub-webhook endpoint (HMAC-validated) that auto-fires a Cloud Bus refresh on
  push — this is what "configure directly from GitHub" means concretely. HTTP Basic
  protected (not JWT — this is a service-identity concern, not an end-user one).
  Explicitly NOT for secrets (DB passwords, JWT secret, internal-service-key, SMTP/SMS
  creds stay as env vars — `config-repo/README.md` states this rule).
- **bmp-monitoring** (new, port 8090): Spring Boot Admin, Eureka-discovery based (every
  registered service shows up automatically, no client dependency needed per service),
  HTTP Basic protected, authenticates to each instance's actuator endpoints via the same
  internal-service-key header used elsewhere.
- Spring Cloud Bus runs over the same Kafka broker bmp-notification already needed.
  `AuthService` (OTP-lockout tuning) and `PaymentOrderService` (manual-status flag) are
  the two concrete `@RefreshScope` examples proving the refresh chain actually works
  end-to-end — the other 7 business services have the config-import/bus plumbing in
  their yml but no `@RefreshScope` bean yet (extensible pattern, not fully retrofitted).
- **Known gaps, unverified:** `spring-boot-admin-starter-server:3.4.1`'s compatibility
  with Spring Boot 3.4.1 was chosen by convention, not confirmed via a real
  `mvn dependency:tree`. `bmp-config-server`'s `default-label: main` and whether
  `devadminbmp/BMP` is public/private are unconfirmed guesses. The GitHub webhook itself
  (Settings → Webhooks → payload URL `http://<host>:8888/monitor`, content type
  `application/json`, secret = `BMP_CONFIG_WEBHOOK_SECRET`) still needs to be added by
  hand in GitHub's UI — not something any AI session can do.

**Reconciliation with the Phase 1 CRUD branch (this session's `git pull`):**
- This session had already flattened every module's package structure (dropped the
  `internal/` wrapper — e.g. `com.bmp.notification.internal.service.X` →
  `com.bmp.notification.services.X`) as uncommitted local changes, while Shivam's
  Session 7 notification-module CRUD commit (and the earlier Phase 1 CRUD branch merge)
  landed upstream still using the old `internal/`-wrapped layout.
- `git pull` fast-forwarded cleanly (no merge conflicts — the upstream diff only touched
  entity field additions and the notification module, nothing that collided with the
  flattening at the git level). Diffed every entity the pull touched (BmpStaff,
  SupportTicket, Booking, PaymentOrder, Review, SalonResponse, Wallet, Salon, SalonHours,
  SalonPolicy, Stylist, StylistSalon, Users) against the already-flattened versions:
  the flat versions were confirmed strict supersets (they already had the mutator/
  `touch()` methods the internal/ versions had), so nothing was lost there.
- The notification module's `internal/`-package CRUD (NotificationLogController/Service/
  Repository) was real new work — a fuller REST API (pagination, `/stats`, a pending-
  queue query, a delivered-status transition) than what existed on the flat side. It was
  rebuilt (not copied) against the flat `NotificationLog` entity, because the original
  was written against column names that don't match the actual V002 migration
  (`recipient_id`/`error_message`/`delivered_at`/`updated_at` vs. the real
  `recipient_user_id`/`error_reason`, and `delivered_at`/`updated_at` didn't exist at
  all). Added **V003__notification_log_delivered_and_updated.sql** for the two genuinely
  new columns; the rebuilt API lives at `GET /api/v1/notifications/recipient/{id}`,
  `GET .../recipient/{id}/pending`, `GET .../stats`, `PUT .../log/{id}/delivered`.
- All stale `internal/` package directories were then deleted (not just git-removed —
  they were still physically present on disk after the pull, which would have meant
  duplicate/conflicting entity classes at build time).
- Also added while root `pom.xml` was briefly writable again (see below):
  `<module>bmp-config-server</module>` and `<module>bmp-monitoring</module>` to the
  `<modules>` list, and restored the `kafka` service to `docker-compose.yml`.
- ⚠️ **A 5-file OneDrive sync issue** (`pom.xml`, `CONTEXT.md`, `README.md`,
  `AI_AGENT_PRIMER.md`, `docker-compose.yml` — every read/write/delete on them failed
  with "No such file or directory" despite `stat` showing correct metadata) persisted for
  the entire session until this `git pull`/`git checkout --` sequence incidentally fixed
  it. Flagging in case it recurs — the fix that worked was `git checkout -- <file>` after
  confirming `git fetch`/`git pull` could still write through where direct
  Read/Write/bash could not.

---

### Session 9 (Darshan/Cowork) — Availability algorithm (Phase 2): freeSlots()/blockWalkIn()

**Darshan asked:** implement the availability algorithm — `AvailabilityApi.freeSlots()` /
`blockWalkIn()` — the interface that had been an intentional stub since Session 4/5
("must be designed on paper against 3 real salons before any table is created").

**Delivered — `AvailabilityService` (bmp-salon), first real implementation of `AvailabilityApi`:**
- Combines four local tables (`stylist_availability`, `walk_in_block`, `salon_hours`,
  `salon_policy.slot_granularity_minutes`) with one live Feign call to a new endpoint on
  bmp-booking-service (`GET /api/v1/bookings/internal/busy-windows`, ROLE_SERVICE-locked)
  that reports a stylist's already-committed bookings (`booking_service_item`, joined to
  `booking` to exclude CANCELLED) and unexpired checkout holds (`slot_lock`) for a date.
- Interval math: working windows (weekly template, or an exception row overriding the
  template for one specific date) minus breaks/leave minus walk-ins minus bmp-booking's
  busy windows, intersected with salon operating hours, sliced into a grid
  (`slot_granularity_minutes`) and kept only where a contiguous run of the requested
  service duration fits.
- `blockWalkIn` does one overlap check against the same busy-window computation, then a
  single insert — deliberately skipped re-validating against declared working hours, to
  stay the "&lt;5-second" front-desk operation the interface's own javadoc demands.
- New shared constant: `com.bmp.common.time.BmpTimeZone.ZONE` (`Asia/Kolkata`, hardcoded —
  BMP is Bengaluru-only right now, see Target Market) for converting bmp-booking's
  Instant timestamps to the LocalTime values bmp-salon's tables use.
- New `AvailabilityController` (`/api/v1/availability/slots`, `/slots/any`, `/walk-in`) —
  api-gateway's salon-service route predicate updated to include it.

**Design questions (Q1-Q6) answered this session — again Darshan-only, not ratified by
Shivam/Achyuth, same flag as every other such decision in this file. Full reasoning for
each is in `AvailabilityService`'s class javadoc, summarized here:**
1. Slot granularity: per-salon grid (`slot_granularity_minutes`, default 15) for the
   *start* time; the required *length* is whatever duration the caller asks for.
2. Walk-in block speed: one overlap check, no working-hours re-validation.
3. Breaks — template AND exception rows both apply and stack (exception can also fully
   replace the day's working windows, not just add a break).
4. Leave with existing bookings: **NOT handled** — marking leave doesn't touch already-
   CONFIRMED bookings. Real gap, flagged not silently resolved (belongs to
   `booking_disruption` / a reschedule-notification flow that doesn't exist yet).
5. Salon hours vs. stylist hours conflict: salon hours are the outer bound, always.
6. Multi-service bookings spanning slot boundaries: not this method's problem —
   `durationMinutes` is the caller's total; splitting across stylists is booking's job.

**Also unverified:** the day-of-week convention used (`date.getDayOfWeek().getValue() % 7`,
i.e. 0=Sunday..6=Saturday) matches the column comments in the V003 migration but hasn't
been checked against how `stylist_availability.day_of_week`/`salon_hours.day_of_week` rows
are actually populated anywhere else in the codebase (no seed/admin UI writes them yet).

---

### Session 10 (Darshan/Cowork) — Wired the availability algorithm into booking creation

**Darshan asked:** continue the booking flow — i.e. actually make `BookingService.create()`
use the availability algorithm from Session 9, instead of trusting whatever stylist/time
the client sends unchecked.

**Delivered:**
- New Feign client on bmp-booking (`SalonAvailabilityClient`) calling bmp-salon's
  `/api/v1/availability/slots` and `/slots/any` — the reverse direction of the Feign call
  bmp-salon already makes into bmp-booking for busy-windows (see docs/AVAILABILITY_ALGORITHM.md
  for why this pair of services now calls each other bidirectionally, and why that's fine).
- `BookingService.create()` now validates every requested item BEFORE writing anything:
  - If the item names a specific stylist, it re-checks that exact `(stylist, start,
    duration)` is still in bmp-salon's live free-slot list. A customer who viewed slots a
    minute ago and lost the race to someone else now gets `409 SLOT_NOT_AVAILABLE`
    instead of a silent double-booking.
  - If the item has no stylist (`any_available`), one is auto-assigned from whichever
    stylist's slot list still has that exact start time free.
  - All items are validated first, then the booking + its items are written — no partial
    writes if item 2 of 3 fails validation.
- No change needed for `cancel()` — the availability algorithm's busy-window query already
  joins to `booking.status` and excludes CANCELLED, so a cancelled booking frees its slot
  automatically the moment `cancel()` flips the status. Verified this is correct by
  construction rather than adding redundant code.
- `slot_lock` (the ~5-minute checkout hold) is still unwired — there's no separate
  "reserve, then pay" flow yet (Razorpay integration is Phase 3), so nothing currently
  needs to write to that table. `bookingBusyWindows()`'s read side already supports it
  the day that flow exists.

---

### Session 11 (Darshan/Cowork) — CRUD gap survey + salon_combo/salon_combo_item

**Darshan asked:** what's actually left across the remaining CRUD modules now that the
teammate branches are merged — every table has an entity+repository, but a survey found
several with no service/controller touching them at all:

| Module | Untouched tables |
|---|---|
| bmp-payment | `webhook_event`, `razorpay_linked_account`, `payout_queue_item`, `payout_batch`, `commission_ledger`, `refund_execution`, `bmp_account`, `saas_subscription`, `saas_invoice` — mostly blocked on real Razorpay integration (Phase 3), not a CRUD gap so much as a not-yet-relevant one |
| bmp-booking | `booking_disruption`, `booking_modification`, `refund_ticket`, `refund_guard` — this is the mechanism that would close the "leave doesn't reschedule existing bookings" gap flagged in docs/AVAILABILITY_ALGORITHM.md §6 (Q4) |
| bmp-rewards | `loyalty_account`, `loyalty_transaction`, `checkout_discount`, `win_back_job_log` |
| bmp-review | `review_edit_history`, `review_prompt`, `salon_rating_snapshot`, `stylist_rating_snapshot` — these look like system-computed/audit tables rather than ones needing direct CRUD, worth confirming rather than assuming |
| bmp-salon | `salon_combo`, `salon_combo_item` — **built this session, see below** |
| bmp-user, bmp-admin | fully covered, no gaps |

**Delivered — salon_combo / salon_combo_item CRUD (`SalonComboService` + `SalonComboController`):**
- `POST /api/v1/salons/{salonId}/combos` — create a combo with its items in one call (a
  combo with zero items isn't a meaningful bundle, so items are required at creation).
- `GET .../combos`, `GET .../combos/{comboId}` — list / get.
- `PUT .../combos/{comboId}` — update name/price/allowsAddons only, never touches items.
- `DELETE .../combos/{comboId}` — deletes the combo and all its items.
- `POST .../combos/{comboId}/items`, `DELETE .../combos/{comboId}/items/{itemId}` — add/
  remove individual items without touching the combo header.
- Same "open pending a follow-up authorization ticket" status as most of `SalonController`'s
  own endpoints — not restricted to the owning salon's OWNER/MANAGER yet.
- A combo id that exists but belongs to a DIFFERENT salon returns 404 (same as "doesn't
  exist"), not 403 — deliberately avoids leaking existence of another salon's combo id to
  an unauthorized caller.

The other gaps above (booking disruption/refunds, rewards loyalty, payment infrastructure)
remain open — flagged for prioritization, not attempted this session.

---

### Session 12 (Darshan/Claude Code) — First real end-to-end local build+run; ~20 latent bugs found and fixed

**Darshan asked:** run every service and build it. Nobody had actually done a clean
build-from-scratch + run-every-service pass since the Session 5 microservices pivot —
every prior session added code against a partially-running dev environment. This
session was the first time anyone tried to go from a fresh clone to all 13 services
actually serving traffic, and it surfaced a long chain of latent bugs that had never
been exercised before. **Full step-by-step for teammates is `RUN_LOCALLY.md`** (new,
repo root) — this log entry is the "what was actually broken and why" record.

**Environment (one-time, per machine, not a code change):** JDK 21 wasn't installed
(only JDK 17) despite the pom requiring it — installed Eclipse Temurin 21 via winget.
Docker Desktop was installed but not running.

**Compile-time bugs (nothing here would have built at all):**
1. `bmp-common`'s `JwtAuthFilter` uses `jakarta.servlet.*` classes but the module never
   declared the servlet API dependency (Spring Security's own servlet dependency is
   `provided` scope, so it doesn't propagate transitively) — added
   `jakarta.servlet:jakarta.servlet-api` (provided) to `bmp-common/pom.xml`.
2. `JwtAuthFilter`'s multi-catch (`ExpiredJwtException | JwtException`) doesn't compile
   — `ExpiredJwtException` is a subtype of `JwtException`. Simplified to just `JwtException`.
3. **Every "Getters only... add bespoke mutation methods per table as real invariants
   surface" entity that a service actually mutated was missing those mutation methods** —
   the doc-comment pattern across every entity class explicitly anticipated this, but the
   services calling `.setX()`/`.touch()` on them were apparently never compiled against
   the entities as they currently stand. Affected: `Users`, `Salon`, `SalonPolicy`,
   `SalonHours`, `StylistSalon`, `Booking`, `PaymentOrder`, `Review`, `SalonResponse`,
   `Wallet`, `BmpStaff`, `SupportTicket`. Fixed by adding the missing setters/`touch()`.
4. **Adopted Lombok** (Darshan's explicit call mid-session: "use Lombok simply for
   everything") — added `org.projectlombok:lombok` (provided) to the root `pom.xml`'s
   shared `<dependencies>` (applies to every module), then rewrote the entities from #3
   to use `@Getter`/`@Setter` instead of hand-written boilerplate. One caveat worth
   knowing: Lombok's boolean-getter/setter naming strips a leading `is` from the field
   name (`isAvailableToday` → `setAvailableToday`, not `setIsAvailableToday`) — where a
   caller already expected the un-stripped name (`StylistSalon.setIsAvailableToday`),
   a manual setter was kept instead of `@Setter` for that one field. Going forward, new
   entities should default to Lombok, not hand-written getters/setters.

**Config files silently truncated mid-file (found by trying to actually boot each
service — these produced confusing runtime errors, not compile errors):**
`bmp-auth/application.yml`, `bmp-notification/application.yml`, and
`api-gateway/application.yml` all cut off mid-line/mid-comment, missing everything
after that point — `baseline-on-migrate`, the entire `eureka:`/`bmp:`/`management:`/
`info:` blocks, and (api-gateway specifically) half the route table including the
entire `notification-service` route. Reconstructed all three against the pattern used
by sibling services. **Worth double-checking other `application*.yml` files for the
same silent truncation** — these three were only found because their absence caused a
hard failure; a file that happens to truncate right at a harmless spot wouldn't announce
itself the same way.

**Missing config values (no default anywhere, `@Value` with no fallback):**
`bmp-auth`'s `AuthService`/`JwtService` require `bmp.auth.access-token-ttl-seconds`,
`bmp.auth.refresh-token-ttl-days`, `bmp.auth.otp-ttl-minutes`, `bmp.auth.otp-max-attempts`,
`bmp.auth.otp-lockout-minutes` — none were set anywhere. Added dev defaults (900s / 30d /
5m / 5 / 15m respectively, all env-var overridable) to `application.yml`.

**Schema ↔ entity type mismatches (Hibernate `ddl-auto: validate` caught these at
boot — Flyway migrations and JPA entities had drifted apart, never previously
exercised together):**
- `SMALLINT` in the migration vs. Java `int` in the entity (Hibernate wants `INTEGER`):
  `bmp-auth.otp_requests.attempts`, `bmp-user.users.age`, `bmp-salon.salon_hours.day_of_week`,
  `bmp-salon.salon_combo_item.sequence`, `bmp-salon.stylist_availability.day_of_week`,
  `bmp-booking.booking_disruption.rejection_count`, `bmp-payment.payout_batch.retry_count`,
  `bmp-review.review.salon_rating/stylist_rating`,
  `bmp-review.review_edit_history.salon_rating/stylist_rating`.
- `salon.location` was `GEOGRAPHY(POINT)` (real PostGIS) in the migration, but
  `SalonService`'s own javadoc confirms hibernate-spatial was never wired in — location
  is actually stored as a plain `"lat,lng"` string with in-memory Haversine proximity
  search, not `ST_DWithin`. Migration widened to `VARCHAR(255)`.
- `TIME` in the migration vs. Java `String` ("HH:mm") in the entity: `salon_hours.open_time`/
  `close_time`, `stylist_availability.start_time`/`end_time`, `walk_in_block.start_time`,
  `booking_schema.slot_lock.start_time`/`end_time`. All widened to `VARCHAR(255)`.

  Fixed via new additive Flyway migrations (never edit an already-applied migration
  file in place — Flyway checksums it): `bmp-auth/V004`, `bmp-user/V003`,
  `bmp-salon/V004`+`V005`+`V006`+`V007`, `bmp-booking/V003`+`V004`, `bmp-payment/V003`,
  `bmp-review/V003`.

**Structural Flyway bug — every business service's migration history collided in the
same physical table:** every service's `spring.flyway.schemas` lists `common_schema`
**first**, and Flyway puts its history table in the first-listed schema unless told
otherwise — so all 9 services were writing their own independently-numbered V001/V002/...
migration history into the SAME `common_schema.flyway_schema_history` table. The very
first two services to actually run against a shared fresh DB in the same session (`bmp-user`
then `bmp-salon`) immediately collided on version numbers with different checksums. This
directly contradicts the intent already documented in every `V001__common_outbox.sql`'s
own comment ("each service's Flyway history is tracked independently and doesn't know
about the others") — the code just never matched that comment. **Fixed by adding
`spring.flyway.table: flyway_schema_history_<service>` to all 9 business services +
bmp-auth**, giving each its own uniquely-named history table regardless of which schema
it physically lives in. Required a `docker compose down -v` reset to clear the already-
poisoned history (that data was only ever this session's own testing, nothing real lost).

**Silent security bug affecting every single business service — the most significant
finding this session:** `com.bmp.common.security.CommonSecurityConfig` (the shared JWT
filter chain, documented as defaulting `public-paths` to `/**`) was **never actually
registered as a Spring bean in any of the 9 business services**. `@EntityScan` (already
present on every service's main class, to pull in `com.bmp.common`'s JPA entities) does
**not** cover `@Configuration`/`@Component` classes — only `@ComponentScan` does, and
none of the main classes had one pointed at `com.bmp.common`. With no custom
`SecurityFilterChain` bean present, Spring Boot silently fell back to its own default
security auto-configuration: HTTP Basic auth behind a **freshly random-generated
password printed to the console on every restart**, blocking literally every endpoint
on every service — Swagger UI, actuator, everything — regardless of what
`bmp.security.public-paths` said. This had been true since whenever these main classes
were first written; it was only discovered now because this was the first session to
actually hit Swagger UI and get a 401 instead of assuming it was working. Fixed by
adding `@ComponentScan(basePackages = {"com.bmp.<service>", "com.bmp.common"})`
alongside the existing `@EntityScan` on all 9 main `*Application.java` classes
(bmp-auth was fixed first/separately — its `AuthService` directly `@Autowired`s
`OutboxPublisher`, another `com.bmp.common` bean, so it failed loudly at boot instead
of silently; that's what led to finding the broader pattern).

**JSONB write bug, also invisible until the security fix above made `OutboxPublisher`
actually reachable for the first time:** `OutboxEntry.payload` (and every other
`@Column(columnDefinition = "jsonb")` `String` field across the codebase) threw
`column "payload" is of type jsonb but expression is of type character varying` on the
very first real insert. Hibernate 6 does not infer the JDBC parameter binding type from
`columnDefinition` alone — it needs `@JdbcTypeCode(SqlTypes.JSON)` (from
`org.hibernate.annotations`/`org.hibernate.type`) alongside it. Fixed on
`OutboxEntry.payload` plus 8 more fields that had the exact same latent bug and simply
hadn't been written to yet: `AuditLog.metadata`, `Booking.policySnapshot`,
`BookingEvents.metadata`, `BookingModification.beforeSnapshot`/`afterSnapshot`,
`NotificationLog.payload`, `PaymentOrder.razorpayRawWebhook`, `WebhookEvent.rawPayload`,
`OnboardingState.stateJson`. **Any future entity with a jsonb column needs this
annotation too — it's not obvious from the compiler or from Hibernate's schema
validation, only from an actual failed write.**

**Dependency version bug:** `springdoc-openapi-starter-webmvc-ui:2.6.0` (pinned
identically across all 9 services) throws
`NoSuchMethodError: ControllerAdviceBean.<init>(Object)` on `/v3/api-docs` under Spring
Boot 3.4.1/Spring Framework 6.2.1 — 2.6.0 predates Boot 3.4 support. Bumped to `2.7.0`
across all 9 `pom.xml`s.

**`bmp-monitoring` missing `spring-boot-starter-web`:** its pom assumed
`spring-boot-admin-starter-server` would transitively bring in a servlet container; it
doesn't. Without one, Spring Security's `HttpSecurity` bean auto-configuration never
fires, so `SecurityConfig.securityFilterChain(HttpSecurity http)` failed to wire at
boot. Added `spring-boot-starter-web` explicitly.

**bmp-notification's `SmtpEmailSender` needs a `JavaMailSender` bean, which Spring only
auto-configures when `spring.mail.host` is actually set** (dependency alone isn't
enough) — added a dev placeholder (`localhost:1025`, nothing runs there) so the service
boots, and set `management.health.mail.enabled: false` so actuator health doesn't flip
to DOWN over a deliberately-absent local SMTP server.

**Dev-only convenience added (Darshan's explicit request — "as i am not using any otp
whats app etc as per now static credentials for dev"):** `bmp-auth`'s `AuthService`
now accepts a fixed master OTP (`bmp.auth.dev-master-otp`, defaults to `000000`,
env-var override `BMP_DEV_MASTER_OTP`) on `/otp/verify`, for **any** phone number, in
addition to the real bcrypt-checked code. Only set on the default profile (what runs
with no `SPRING_PROFILES_ACTIVE`, i.e. every local dev machine) — deliberately absent
from `application-staging.yml`/`application-prod.yml`, so it's disabled there by the
`@Value("${bmp.auth.dev-master-otp:}")` default of blank. You still need to call
`/otp/request` first (an OTP record has to exist) — this bypasses the *code check*, not
the whole flow. Documented in `RUN_LOCALLY.md` §7.

**End-to-end verification actually performed** (not just "it compiled"): `docker
compose up -d` → all 13 services started clean from the fixed code → `POST
/api/v1/auth/otp/request` → `POST /api/v1/auth/otp/verify` with `otp: "000000"` → real
JWT returned → `GET /api/v1/users/{id}` with that token as a Bearer header → 200 with
the created user's data. This is the first time this specific chain (build → boot all
13 → login → authenticated call) is known to have actually been run successfully.

**Deliverable — `RUN_LOCALLY.md` (new, repo root):** the actual onboarding doc for
teammates pulling this repo — required tool versions, `git clone`/`pull`, one-time
build, Docker Desktop setup (with a per-container Postgres/Redis/Kafka breakdown and an
explicit explanation of why `docker-compose.yml` alone doesn't start the 13 app
services — that file only ever defined infra, and its own top-of-file comment claiming
otherwise is stale, left over from the pre-Session-5 `bmp-app` monolith), start order
for all 13 services with copy-pasteable commands (plus a one-terminal
`Start-Job`-based alternative), a health-check script, the full Swagger UI / OpenAPI
JSON URL table, the login flow (including the dev-master-otp), and a troubleshooting
table built directly from the bugs hit this session.

**Not done / still open, flagged not silently skipped:**
- None of this session's ~63 changed/new files are committed — see the Current Status
  warning at the top of this file.
- Real Razorpay/WhatsApp/SMS/email provider integration remains untouched (Phase 3, as
  already planned) — Darshan explicitly confirmed leaving these as-is this session.
- Didn't audit every remaining `application*.yml` (dev/local/staging/prod variants) for
  the same silent-truncation pattern found in 3 files — only the ones that actually
  failed to boot were checked and fixed.
- Didn't check whether the JSONB/`@JdbcTypeCode` bug affects any entity outside the 9
  fixed here that simply hasn't been written to yet by any currently-existing code path
  — worth a proactive `grep -rn 'columnDefinition = "jsonb"'` sweep again after future
  entities are added.

---

### Session 13 (Darshan/Cowork) — Service-by-service completion pass, starting with bmp-user

**Darshan asked:** go service by service (working dependent services together), starting
with bmp-user; think deeply about everything it should have. WhatsApp/email/Razorpay
stay stubbed as before.

**Audit findings (real gaps, not cosmetics):**
1. `users.phone` had NO actual UNIQUE constraint — V002's comment claims "UK" but never
   declared one; only UserService's racy app-level `existsByPhone` check existed.
2. `is_verified` was never set true anywhere — every user, all of whom are created only
   AFTER passing OTP verification, was stored permanently unverified.
3. `user_roles` had no dedup — the same role was grantable to the same user unlimited times.
4. `onboarding_state` was completely unused (entity+repo existed, no service/controller),
   despite Module 1's spec defining its exact lifecycle.
5. Zero authorization — with `public-paths` defaulting to `/**`, anyone could look up any
   user by phone, create users, or grant themselves roles.

**Fixed/built (bmp-user + its dependent, bmp-auth):**
- **V004 migration:** `uk_users_phone` UNIQUE constraint; role-dedup unique index
  (COALESCE(salon_id, zero-uuid) because Postgres treats NULLs as distinct);
  `deactivated_at` column.
- **Create-verified:** users are now stored `is_verified=true` at creation, with a javadoc
  note to add an explicit flag if a pre-verification creation path ever appears. Both
  `create` and `addRole` also catch the DB constraint violation as a race-proof 409.
- **Soft deactivation (Instagram-style):** `POST /{id}/deactivate` (self), auto-reversed
  by the user's next successful OTP login — bmp-auth's verify flow now checks
  `deactivatedAt` on the fetched UserDto and calls the new internal
  `POST /{id}/reactivate`. No separate reactivation UX needed.
- **Roles completed:** dedup on grant (409 ROLE_ALREADY_GRANTED), revocation
  (`DELETE /{userId}/roles/{roleId}`, service-only, refuses to remove the current
  default role), and default-role switching (`PUT /{userId}/default-role`, self, must
  actually hold the role) — the "stylist who also books as a customer" case.
- **Onboarding state lifecycle:** `PUT`/`GET`/`DELETE /{userId}/onboarding-state` —
  wholesale-replace on save, 404 when nothing in progress, deleted on completion, exactly
  per Module 1's "transient crash-recovery table" spec.
- **Authorization pass (first business service to get one):** `public-paths` tightened to
  swagger+health/info; every endpoint `@PreAuthorize`d. Model: `ROLE_SERVICE` can do
  everything; end users only their own record (`principal.userId() == #userId`);
  create/phone-lookup/role-grant/role-revoke/reactivate are service-only (phone→profile
  resolution for arbitrary numbers is an enumeration risk).
- **Validation hardening:** gender whitelist (male/female/other), age 1-120, `@Email`,
  role whitelist (lowercase, matching the DB convention and what bmp-auth actually sends).

**⚠️ Cross-cutting latent bug found and fixed while doing this (affects EVERY service):**
the JWT `role` claim is stored lowercase (`salon_owner`), and `JwtAuthFilter` built the
Spring authority as `ROLE_salon_owner` — but every `@PreAuthorize("hasRole('SALON_OWNER')")`
check is uppercase and case-sensitive, so **every role-gated endpoint (salon creation,
manager invites) has been silently 403ing legitimate users since Session 6**. Never caught
because no session had exercised a role-gated endpoint with a real user token (Session 12's
end-to-end test hit user-fetch, which had no role gate). Fix: `JwtAuthFilter` now
uppercases the authority only (`ROLE_` + role.toUpperCase()); stored/claimed values stay
lowercase.

**Not done, deliberately:** no user list/search endpoint (nothing needs it yet — add for
the admin dashboard when bmp-admin's own pass happens); no hard-delete/DPDP data-erasure
flow (needs a real cross-service design — bookings/reviews reference user ids — worth a
ticket before public launch); profile photo upload still just stores a URL string
(Cloudflare R2 integration is Phase 3-adjacent, per "keep providers stubbed").

---

### Session 14 (Darshan/Cowork) — Auth made frontend-ready (all 4 roles)

**Darshan asked:** make login/signup solid for every role (customer, salon owner, manager,
stylist) so frontend work can start against a stable contract. WhatsApp/email/Razorpay stay
stubbed. Also: from here on, thorough dev-facing comments AND real logging on everything.

**Audit result:** all four role flows already worked functionally, but the API contract had
gaps that would have forced the frontend to decode JWTs and guess. Fixed:

- **Login/refresh/google responses now carry routing info.** `OtpVerifyResponse` gained
  `role`, `salonId`, and `isNewUser` (signup vs login); `GoogleAuthResponse` and
  `RefreshResponse` gained `role`/`salonId`. The frontend can now route to the right home
  screen and decide onboarding-vs-home straight from the response body.
- **New `GET /api/v1/auth/me`** — the whoami/session-restore call. Bearer token in, identity
  + role + salon + profile subset out, one round-trip. It's the ONLY authenticated endpoint
  on AuthController; `bmp.security.public-paths` was tightened (was the `/**` default) so the
  token-issuing endpoints stay public but `/me` requires a valid token.
- **Resend timer:** `OtpRequestResponse` gained `resendAvailableAt`; the 55s cooldown is now
  the named constant `AuthService.OTP_RESEND_COOLDOWN_SECONDS`, so the frontend shows an
  accurate countdown instead of hardcoding it.
- **Real SLF4J logging** across AuthService at every meaningful point (OTP issued, verify
  ok/fail/lockout, each role's signup path, google linked/unlinked, refresh, logout,
  reactivation, dev-master-OTP usage as a WARN) — phone numbers masked in logs
  (`+91******10`), OTP codes and tokens never logged.
- **Deliverable: `docs/AUTH_API.md`** — the frontend's single reference: every endpoint with
  exact request/response JSON, the full per-role signup+login journey, error codes, and the
  dev-OTP note.

**Clarified in code, not a gap:** there is no "forgot password" endpoint because there is no
password — OTP re-login IS recovery (documented in AuthService's javadoc and AUTH_API.md).

**Google Sign-In configured (mid-session):** a Google Cloud OAuth Web client (project
`BMP2026`) was created; its Client ID is now the default for `bmp.auth.google-client-id`
(not a secret, safe in-repo). `/oauth2/google` is live in local dev. Mobile clients later
will need the verifier to accept a LIST of client ids (different `aud` per platform) — still
a single id today.

**Email delivery switched on (mid-session):** `bmp-notification`'s email path is now
config-driven — `bmp.notification.email-provider` = `log` (default, console) or `smtp` (real
JavaMailSender delivery, `spring.mail.*` from `BMP_SMTP_*` env vars, STARTTLS, Gmail
defaults). `LoggingEmailSender`/`SmtpEmailSender` are now mutually-exclusive
`@ConditionalOnProperty` beans (was a hardcoded `@Primary`). **SMS/WhatsApp deliberately
LEFT as the log stub** (Darshan's call — SMS needs India DLT registration, deferred; the
`SmsSender` interface means it's a drop-in later). SMTP password is env-var only, never
committed.

**Reminder still standing from Session 13:** the `hasRole` case fix (JwtAuthFilter uppercases
the authority) is what makes SALON_OWNER-gated endpoints — including the salon-creation and
manager-invite steps in the owner/manager journeys above — actually work. Verify that flow
on the next real local build.

---

### Sessions 15–19 (Darshan/Cowork) — Salon staff, availability, walk-ins

Backend half of the frontend work logged in `../BMP-FE/CONTEXT.md` sessions 15–19.

**bmp-salon**
- `V008__staff_invites_role.sql`; `StaffService` — owner adds and removes managers, issues
  invites. Role is granted by someone who already owns the salon, never claimed at signup.
- `StylistAvailabilityService` / `Dtos` / `Controller` — weekly hours + dated overrides.
- `InternalSalonController`, `AdminServiceClient`, `UserServiceClient`.

**bmp-auth** — stylist and manager invite consumption in `AuthService`.

**Two bugs caught before they shipped, both silent-failure class:**

1. **Weekday convention.** `stylist_availability.day_of_week` was being written Monday=0, but
   the existing reader uses `getValue() % 7`, i.e. **Sunday=0**. Every stylist's hours would
   have landed on the wrong day, with no error anywhere.
2. **`dayOfWeek` was a primitive `int`** on a nullable column. Hibernate throws reading SQL
   NULL into a primitive. Changed to `Integer`.

**`SalonService.near()` returned every salon regardless of status**, which made the whole
moderation gate decorative — an unapproved salon was publicly bookable. Now filters on
`PUBLICLY_VISIBLE = List.of("approved", "active")`. Both values are accepted deliberately: the
seed writes `'active'` and moderation writes `'approved'`, and filtering on only one would have
emptied every dev environment.

### Session 20 (Darshan/Cowork) — bmp-admin: the staff console service

A new service, and the first one with an identity model of its own.

- `V003__admin_console_hardening.sql`, `V004__staff_activation.sql`, `V005__refund_request.sql`
- `TotpService`, `StaffPermission`, `AdminJwtService`, `StaffAuthFilter`, `AdminSecurityConfig`,
  `StaffBootstrap`, `StaffAuthService`, `StaffAdminService`
- `ConsoleController`, `SupportDeskController`, `PlatformSettingService`, `RefundService`,
  `PiiMasker`
- Entities: `SalonReview`, `DataRequest`, `PlatformSetting`, `ContentReport`, `RefundRequest`,
  `StaffSession`, `StaffActivation`

**Design rules established, all of them enforced in code:**

- Staff identity is `admin_schema.bmp_staff`, **separate from customers**. There is no
  "promote user to admin" path, because there is no role on a customer account that means
  anything here.
- A **different JWT signing key** (`bmp.admin.jwt-secret`) and a `bmp-admin` audience claim,
  checked explicitly. If the two shared a key, compromising the consumer stack would hand over
  the console.
- PII masked at the **API boundary**, not the UI. Revealing a field costs a typed justification
  and writes an audit row. Masking in the UI would leave the real value in the network log.
- The audit log is append-only at the **database** level: `REVOKE UPDATE, DELETE`.
- Four-eyes on refunds — you cannot approve one you raised.

**Security holes found and closed while building it:** `SupportTicketController` sat outside the
admin security matcher, so **any customer token could read and edit every ticket, including
internal notes**.

**A mistake worth recording permanently.** V003 originally seeded the superadmin with
`$2a$10$N9qo8uLOickgx2ZMRZoMye…` — a bcrypt hash copied from Spring Security's own
documentation, i.e. **publicly known** — under a comment claiming it was the hash of
`ChangeMe#2026`. Replaced with the literal `LOCKED-NO-PASSWORD-SET`, which is not a valid
bcrypt hash and can therefore only fail verification, plus `StaffBootstrap` for a one-time
environment-variable claim. Remediation SQL for anyone who applied the early version is in
`../BMP-ADMIN/RUN_LOCALLY.md` §2c.

### Session 21 (Darshan/Cowork) — Authorization pass: auth, booking, salon

**`BookingController` had no authorization at all.** Any authenticated customer could read,
modify or cancel **any** booking by guessing or enumerating an id. Full rewrite.

Also added `V005__booking_discount.sql`, `InternalBookingController`, `RewardsServiceClient`,
and `BookingService.applyCouponIfPresent`.

**A bug inside the fix:** `applyCouponIfPresent` caught `ResponseStatusException` instead of
`FeignException`, so **every coupon refusal** — expired, already used, wrong salon — surfaced as
"try again later". Now branches on `e.status()`; 409 and 404 pass the real message through.

`bmp-booking`'s `bmp.security.public-paths` was **absent**, and the code default in
`CommonSecurityConfig` is `/**`. An omission fails open. Set explicitly.

### Session 22 (Darshan/Cowork) — bmp-rewards: coupons and referrals

`V003__coupon_targeting.sql`, `CouponIssuePolicy`, `CouponAdminService`,
`CouponRedemptionService`, `ReferralService`.

Audiences: all users, selected users, all salons, selected salons, new users, referred users.
**Support can only issue to selected users, and only against a ticket** — a support agent should
not be able to discount the entire platform from a chat window. Enforced in `CouponIssuePolicy`
server-side, not by hiding a form field.

- Redemption happens **after slot validation, inside the transaction**, with a pessimistic row
  lock on the usage cap. Order matters: redeem-then-validate burns a coupon on a booking that
  fails.
- `public-paths` here also fell back to `/**` — nothing was authenticated. Tightened.

### Session 23 (Darshan/Cowork) — Backend completion pass

Settings, content reports, refund approve/reject, real ops numbers rather than placeholder
counts, and Feign wiring from bmp-admin to user/salon/booking/rewards.

Fixed: nested Spring Data repository interfaces inside a container class (split into separate
files to avoid scanner risk), and duplicate hand-written getters on `Booking`, which already has
Lombok `@Getter`.

**An SLA clock that was structurally incapable of firing** — nothing enqueued salons for review,
so the moderation queue would have stayed permanently empty and the SLA would have looked
healthy forever.

### Session 24 (Darshan/Cowork) — Test credentials

`docs/TEST_CREDENTIALS.md` — every role, in all three apps, with the honest note about what can
and cannot actually be signed into today.

### Session 25 (Darshan/Cowork) — First real run of bmp-admin, and a documentation pass

**`bmp-admin` would not start.** Two top-level `bmp:` keys in `application.yml` (line 46 and
line 90 — the second was leftover Session 6 boilerplate duplicating two properties already
present in the first):

```
org.yaml.snakeyaml.constructor.DuplicateKeyException: found duplicate key bmp
```

The crash is the lucky outcome. **YAML does not merge mappings per-leaf** — had the parser been
lenient, the second `bmp:` would have replaced the first one wholesale, wiping
`bmp.security.public-paths` and the entire `bmp.admin` section. The service would have started
with no admin signing key, no console origin and no bootstrap config, and login would have
failed somewhere that pointed nowhere near this file. Checked all 14 services: no other
duplicates.

**Actuator surface on bmp-admin tightened.** It exposed `env`, `beans`, `threaddump` and
`heapdump`. Only `/actuator/health` and `/actuator/info` are public, but `CommonSecurityConfig`
validates signature and expiry — **not audience** — on non-admin paths, so an ordinary
*customer* token satisfied the rest. Any signed-in customer could have pulled a heap dump from
the one service holding `bmp.admin.jwt-secret` in memory, and that key mints `super_admin`
tokens. Now `health,info,metrics,loggers,refresh,busrefresh`.

**The gateway had no route for `/api/v1/admin/**`.** The console's axios baseURL is
`/api/v1/admin` and its Vite dev proxy forwards `/api` to the gateway, so every console request
against a real backend 404'd before reaching bmp-admin. Nothing in the console or the service
was wrong — the request never arrived. Added to the `admin-service` predicate.

**`DevStaffSeeder` — one account per console role, local only.** The production onboarding path
(bootstrap superadmin → enrol 2FA → create employee → hand over a single-use code → they enrol
2FA) is correct and unchanged, but it's ~5 minutes *per role* after every
`docker compose down -v`. Nobody was ever testing as a support agent or a read-only analyst,
which meant the least-privilege rules the console rests on went unverified.

Three guards, and **the second is the interesting one**: it checks the **JDBC URL is
localhost**, not `@Profile("dev")` — because in this repo the `dev` profile points at a
**shared Neon branch**. A profile-gated seeder would have written known-password admin accounts
into a database three founders share. Profile names lie; a JDBC URL doesn't. Password is random
per run and printed once to the console — never in a migration, for the reason V003 taught us.

Docs: this update, `../BMP-ADMIN/CONTEXT.md` (new), `../BMP-ADMIN/RUN_LOCALLY.md` (new),
`../BMP-FE/RUN_LOCALLY.md` (new, replacing `RUN.md`), `docs/TEST_CREDENTIALS.md` §3,
`RUN_LOCALLY.md` §0/§5b/§10b, and `../BMP-FE/CONTEXT.md`.

**Standing note for whoever picks this up:** the authorization state table in
`RUN_LOCALLY.md` §8 is now the accurate one. The old "most endpoints are wide open" line was
out of date and would have led someone to assume a gap that had been closed — or worse, to
assume one had been closed when it hadn't.

### Sessions 29–30 (Darshan/Cowork) — The access audit, and the booking-price hole

**Session 29 — every endpoint, documented and gated.** `docs/API_ACCESS.md` now covers all 166
endpoints with the rule that guards each and why. 138 carry an explicit rule (was 121); the rest
are auth endpoints that must be public, plus bmp-booking's five, which enforce ownership in the
method body because "self or staff of the salon this booking belongs to" needs a DB lookup SpEL
can't do — **a scan for `@PreAuthorize` reports those as unprotected and is wrong**.

*The structural fix:* `CommonSecurityConfig` no longer defaults `public-paths` to `/**`. A
service that omits it now **fails to start**. Four services had omitted it, which is how 52
endpoints came to need no credential — none of it a decision, all of it the same omission
repeated. Splitting one process into thirteen turned one authorization decision into thirteen
chances to forget one.

*The worst finding:* **`POST /api/v1/staff` creates a BMP staff account**, sits outside
`AdminSecurityConfig`'s `/api/v1/admin/**` matcher, and had no `@PreAuthorize` — so it fell to
the shared chain, which accepts any valid JWT. **Any logged-in customer could create themselves
a staff account with one call.** `/api/v1/audit-log` was the same, exposing the record of which
staff member viewed which customer's phone number and why. Both are now SERVICE-only and
`@Deprecated(forRemoval = true)`.

This was the **second** time a controller outside an intended matcher proved wide open
(`SupportTicketController`, Session 20). A `securityMatcher` protects a path prefix, not a
service. Written down as a rule in API_ACCESS.md §8.

*Also closed:* `POST /coupons` (minted coupons — a customer could make themselves 100% off),
`POST /admin/wallet/credit` (created money from nothing; **a path is not a permission** — the
`/admin/` prefix protected nothing), `GET /users/{id}/wallet` (readable for any user id by
changing the UUID — classic IDOR), `PUT /payment-orders/{id}/status` (marks a payment captured;
on a public server, "book anything for free"), and every salon write, which now checks
`principal.salonId().equals(#salonId)` as well as the role — the role says what *kind* of thing
you are, `salonId` says *which one*, and omitting the second half is invisible when you test
with one tenant.

**Session 30 — the booking price came from the client.** `ItemRequest` carried `nameSnapshot`,
`pricePaise` and `durationMinutes`, and `BookingService.create` wrote them straight to the
database. A hand-rolled POST booked a ₹4,500 service for ₹1.

**The duration half was worse, and less obvious.** `resolveAndValidateSlot` passed
`item.durationMinutes()` — the *client's* number — to bmp-salon's availability check. Understate
a 120-minute service as 15 and the question asked is "is there a 15-minute gap here?" There is,
so the booking is accepted, written at 15 minutes, and then overruns the next three
appointments. **Validating against a length the caller chose is not validation.**

Both fixed by fetching the salon's menu over Feign and using its values; the request's are
ignored (fields kept, marked `@Deprecated`, so older clients don't 400). If the menu can't be
loaded the booking is **refused** rather than falling back — a booking at an unverified price is
worse than one that didn't happen, because the salon has to honour the first.

**Session 30 (cont.) — commission became data, and the policy snapshot started existing.**
`V009__salon_commission.sql` adds `salon_policy.commission_bps` (basis points, integer, default
1200 = today's hardcoded rate, CHECK 0–5000). Commission was `total.percentBps(1200)` — the same
12% for every salon, unchangeable without a deploy. A launch offer or a negotiated rate for an
anchor salon was not expressible, which would have ended the first commercial conversation with
"we can't do that".

Owners can **see** their rate but not set it: `commissionBps` is nullable on the request and
null means "leave unchanged". Reading it unconditionally would let an owner zero their own rate
by adding a field — and, far more likely, would silently reset a negotiated rate whenever a
client saved an unrelated change without sending it.

**`policy_snapshot` was the literal string `"{}"`**, on a column whose own migration comment
reads *"FROZEN copy of salon_policy, never changes"*. It froze nothing. It now captures the
cancellation terms at booking time, so a salon later switching from `flexible` to `strict`
cannot retroactively change what an existing customer agreed to. A salon with no policy row is
normal and does **not** refuse the booking — it falls back to platform defaults and records
`"source": "platform_default"` in the snapshot, because in a dispute "the salon chose these
terms" and "nobody had chosen any" are different facts. Contrast the service menu, which *does*
refuse: there is no safe default for someone else's price.

**A correction to the Session 29 audit.** It reported "cancelling a booking burns the coupon",
from a `TODO` in `CouponRedemptionService:138`. That TODO had been stale for eight sessions —
`BookingService.cancel` has released coupons since Session 22. The audit that warned "trust call
sites, not prose" then made exactly that mistake. Comment corrected; `PENDING_WORK.md` marks the
item as never-true rather than fixed.

### Session 31 (Darshan/Cowork) — Coupon requests, approvals and spend allowances

Darshan's spec: support should hold only a small discount budget; anything more goes to admin as
a request; **salon owners can raise requests too**, admin creates the coupon, the salon then
gives it to their customers; and admin manages what support is allowed.

`V004__coupon_requests.sql` + `CouponRequestService` + `CouponAllowanceService`. Full write-up:
`docs/COUPON_REQUESTS.md`.

**The gap this closes.** Session 22's limits (₹500 / 20% / 1 recipient / 30 days) were the right
wall, but a wall with no gate fails predictably: *"the salon cancelled this customer's wedding
booking, ₹500 isn't enough"* → 403 → the agent either gives up or borrows an admin login. **A
refusal with no path forward doesn't enforce a policy, it makes people route around it.**

**Second gap: per-coupon limits are not a budget.** One agent could have issued a hundred
compliant ₹500 coupons in an afternoon, unnoticed until the month's numbers. There is now a
rolling 7-day allowance on count *and* total value, and exceeding it routes to the request flow
rather than failing.

**Design decisions worth keeping:**

- **Rolling window, not calendar.** A calendar reset creates a use-it-or-lose-it rush at
  month-end — the last incentive you want on an apology budget.
- **Percentage coupons count at their cap**, not their expected value. The allowance measures
  exposure, and the honest worst case is the cap.
- **Revoked coupons still count.** Otherwise the limit is bypassed by churn and measures nothing.
- **Approve-with-modification** (`approvedValue`, `approvedMaxDiscountPaise`, `approvedActiveTo`).
  An approver who can only say yes or no says **no**; "you asked ₹2,000, here's ₹800" is the
  answer most of the time, and reject-then-re-ask is a round trip nobody makes.
- **The queue is oldest-first**, because a goodwill request has an unhappy customer already
  waiting behind it. Newest-first is the default everywhere and wrong in every queue.
- **`cancelled` ≠ `rejected`** — withdrawal vs refusal. Collapsing them makes approval rate
  meaningless.
- **A salon owner's request is forced to their own salon from the token**, so a platform-wide
  ask is impossible rather than possible-and-refused. Refusing at approval time is too late: by
  then it looks like a decision someone made.
- **Allowance overrides are data, not a role.** "Senior support" as a fourth role means a new
  permission matrix for one number.
- **Minimum text lengths** on justification (20) and rejection notes (10). A required field that
  accepts "." is required in name only.
- Coupons are minted under the **approver's** identity — the honest attribution is who
  authorised it, not who asked.

### Session 32 (Darshan/Cowork) — Telling people things happened

The console UI landed (see `../BMP-ADMIN/CONTEXT.md`) and the salon-owner Offers tab (see
`../BMP-FE/CONTEXT.md`), which left one gap: **an approval workflow where neither side was ever
notified.** A raised request sat in a queue nobody was told about; an approval never reached the
requester. That is the same failure the workflow exists to prevent, moved one step along.

Two events on the existing outbox → Kafka → `NotificationDispatcher` path:
`coupon_request.raised` → the ops address, `coupon_request.decided` → the requester.

**Published inside the request's transaction.** A rollback takes the notification with it —
otherwise you send "your request has been received" for requests that don't exist.

**bmp-rewards gained its first Feign client** (`UserServiceClient`), because contact details are
resolved at RAISE time and stored on the row. `NotificationDispatcher` deliberately holds no
clients — every event carries the address it needs, which keeps the dispatcher a dumb delivery
mechanism instead of fanning out a dozen lookups per message. So whoever emits owns the address.

Resolving at raise rather than at decision matters twice over: the approval transaction also
mints a coupon, so an outbound call in there means bmp-user being briefly down takes the
approval with it; and the address you should answer is the one on file when they asked.

Smaller calls: **one ops address, not per-admin routing** (no distribution list exists, and
encoding whose problem each request is duplicates the queue); **the summary and reason go in the
body** so a decision can be triaged from a phone; **SMS only on approval with a code**, because
a rejection's reasoning doesn't fit in 160 characters and a truncated refusal reads worse than
none. Unset `BMP_OPS_EMAIL` logs a warning rather than failing silently.

`requester_phone` was added to V004 in place rather than as a V005 — V004 was written the same
day, has never been applied anywhere, and a fresh migration for a column forgotten minutes
earlier is worse than editing an unreleased one. **If you did apply V004 locally, reset the
volume; the checksum will mismatch.**

**Still not built:** an aggregate view of platform-wide coupon spend, and `expireStale()` runs on
queue read rather than on a schedule. The default numbers (10 coupons / ₹3,000 per week) are
deliberately tight placeholders, not a business position.

### Session 33 (Darshan/Cowork) — CI, because nothing had been compiled since Session 25

**The honest state before this:** roughly thirty Java files written or edited across Sessions
26–32 — a new Maven dependency in bmp-rewards, three migrations, two entities, a Feign client,
authorization annotations across nine controllers — and **not one line of it compiled**. The
work happened in an environment with no JDK, and "I'll build it later" is a promise nobody
schedules.

The cost of that isn't the bugs. It's that they arrive **in a batch**, at the moment somebody
needs the thing to run — which is usually the day before a demo. A compile error found ninety
seconds after a push is a non-event; thirty files' worth found a fortnight later is an
afternoon, in the worst possible week.

`.github/workflows/ci.yml` in all three repos. `mvn -B -DskipTests verify` on the backend,
`tsc` (+ `vite build` on the console) on the frontends.

**`-DskipTests` is honest rather than aspirational.** There are no tests. The job answers one
question — *does it build* — and answers it reliably, which beats a job that claims more and
gets switched off when it's flaky. Same reason nothing here uses `continue-on-error`.

**Four non-compiler guards, each because the thing has already gone wrong:**

- **`public-paths` declared, and not `/**`** — the omission has happened FOUR times, leaving 52
  endpoints reachable with no credential. `CommonSecurityConfig` now fails at startup without
  it, but "fails at startup" only helps if somebody starts the service, and a service nobody
  runs locally gets merged unstarted.
- **Console routes exist** — three dashboard tiles once pointed at routes that never existed.
  React Router can't warn: navigating to an unmatched path is a legitimate navigation to the
  catch-all.
- **`auth.ts` never references `USE_MOCKS`** — every other API is mocked behind a flag that
  defaults to ON. If auth joined them, one forgotten env var ships an authentication bypass.
- **The console's demo-login block stays gated** — it lists five working sign-ins and accepts
  any four-character password.

Plus a *warning* (not a failure) when an existing migration is edited: sometimes that's right,
as with V004's `requester_phone` this week, and the judgement belongs to a human who knows
whether it shipped.

**All four guards were run green against the current tree before being committed.** A check that
fails on day one gets switched off on day two.

Also done this session: a static sweep of every uncompiled Java file for unresolved imports,
Lombok getters without fields, and record-arity mismatches. It found nothing — which is
reassuring but weak evidence, and precisely why the compiler needs to run.

### Session 34 (Darshan/Cowork) — bmp-booking learns to tell someone

**The finding:** bmp-booking published no cross-service events whatsoever. A customer could book
an appointment and receive **nothing** — no confirmation, no reminder, no notice of their own
cancellation. The only message BMP had ever sent them was their login OTP.

**Why it went unnoticed for eight sessions**, which is the part worth keeping. Every piece of
plumbing was already in place: the outbox table in bmp-booking's schema, `@EntityScan` over
`com.bmp.common` *with a comment saying it was there for `OutboxPublisher`*, and the relay
draining to Kafka. `OutboxPublisher`'s own usage example, written in Session 3, is
`outbox.publish(new BookingCompleted(...))` — an event that did not exist.

The trap was one line in `BookingService.create`:

```java
recordEvent(booking.getId(), "CREATED", "customer", req.customerId(), Map.of());
```

That writes to `booking_events`, bmp-booking's own append-only audit trail. It is correct and it
does its job — and it *reads* like publishing. Two systems with "event" in the name, one local
and one cross-service, and the local one was written first. **Nothing ever failed**, because a
missing event is silence rather than an exception. The same shape as the stale `TODO` that made
Session 33 report C1 as broken when it never was: prose and near-miss code both outrank memory,
and neither outranks a call site.

**What was built**

| Piece | Why |
|---|---|
| `V006__booking_contact_snapshot.sql` | `customer_name/phone/email` + `salon_name_snapshot` on the booking row. Additive, nullable, no backfill. |
| `BookingCreated` / `BookingCancelled` / `BookingCompleted` | In `bmp-common/events`, following the emitter-carries-contact rule already documented in bmp-rewards' `UserServiceClient`. |
| `bmp-booking/client/UserServiceClient` | One method, four fields. Called **once per booking**. |
| Three handlers in `NotificationDispatcher` | Email + SMS, composed separately rather than one truncating the other. |
| Customer name + masked phone on `ScheduleEntryResponse` | The desk previously showed a bare UUID. |

**The three decisions that carry the design**

1. **Snapshot the contact, don't look it up.** `cancel` and `salonTransition` publish too. If
   they resolved contact live, a customer could not cancel their appointment while bmp-user was
   restarting — and being unable to cancel is far worse than a stale phone number. The accepted
   cost is that a number changed after booking is stale; the fix if it ever bites is to refresh
   on the reminder job, **not** to make the booking path depend on a live lookup.

2. **A contact-lookup failure does not fail the booking.** This is the deliberate opposite of
   the `serviceMenu` call twenty lines above it, and the distinction is the whole design. Price
   is part of the agreement and cannot be guessed, so an unreachable bmp-salon *must* refuse the
   booking. Contact details are not part of the agreement — a customer whose appointment was
   accepted but whose SMS never sent still has an appointment; it's in the app. Refusing the
   booking to protect the receipt would be backwards.

3. **The phone is masked in the service, not the UI.** A client that masks is a client that
   received the real number. Beyond DPDP, a day view showing full numbers is a downloadable
   customer list, and a salon that can harvest BMP's customers can take them off-platform —
   which is the commission walking out the door.

**What it deliberately does not say.** The message says *"requested"*, not *"confirmed"* —
bookings sit in `PENDING` until the Razorpay webhook (Phase 3), and telling someone their
appointment is secured before anyone has taken payment is a promise the platform has not made.
No refund figure on cancellation: that depends on `policy_snapshot` and a payment that doesn't
exist, and a confident wrong number in writing arrives later as a chargeback. `NO_SHOW` sends
nothing — an automated accusation on the salon's unilateral say-so, with no right of reply,
needs a policy before it needs code. No review prompt on completion until bmp-review verifies
attendance (S3).

Left open, in `docs/PENDING_WORK.md` §5b: **N3 reminders** (the highest-value item left in that
file), **N4 audited reveal-phone** and **N5 templated salon→customer messages** — until those
ship, the honest answer to "how does a salon reach a customer who's late?" is *it can't*.

### Session 35 (Darshan/Cowork) — the salon can reach the customer, and can look things up

Two things Darshan asked for after Session 34: a way for the salon to **call** a customer, and
the **history** screen.

**Calling — `POST /bookings/{id}/reveal-contact`**

The day view shows `98765 4••••`. Showing the whole number would have been one line less code,
and would have made every screen a manager opens a downloadable customer list. Two costs, and
the second survives a change of ownership: a salon that can harvest BMP's customers can take
them off-platform — that is the commission walking out the door, and the commonest way a
marketplace is disintermediated by its own supply side — and under DPDP, BMP is the data
fiduciary for that leak regardless of who exported it.

So it is a deliberate action. A salon with a real reason pays one extra tap. A salon quietly
building a contact list pays a permanent, per-customer record of doing so.

Three decisions inside it:

1. **The audit entry goes to `booking_events`, not bmp-admin's `audit_log`.** Deliberate, and
   not for convenience: `GET /bookings/{id}/events` is readable by **the customer**. They can see
   in their own app that the salon looked up their number and why. *An audit trail only the
   platform can read protects the platform; one the data subject can read protects them.* Given
   this endpoint exists to hand out a personal phone number, it should be the second kind.

2. **POST, not GET** — it reads data and returns it, which sounds like a GET, but it *writes a
   permanent record every call*. A GET that mutates is one browsers, proxies, retry logic and
   link prefetchers will call again on their own, and every one would be an unexplained entry in
   a customer's booking history. There is also no `useQuery` wrapper on the client, for the same
   reason: a query refetches on focus and reconnect.

3. **A fixed reason list, not a text box.** Free text becomes "." within a week, and a reveal
   nobody can review later may as well not have been recorded.

**Masked calling is the intended end state** (BMP bridges, neither party sees the other's
number). It needs a telephony provider and a registered company — Track 0, not code.
`revealCustomerContact` is the seam: it becomes "place a bridged call", the number stops being
returned, and the reason, the audit entry and the customer's visibility of it are all unchanged.
That is *why* the reason is collected server-side on a POST rather than logged client-side.

**History — `HistoryPanel`, on both the owner and manager desks**

`GET /bookings/salon` was written, secured and paginated in **Session 16** and nothing ever
called it. An owner could see today and had no way to look up last Tuesday — which is most of
what an owner wants from a booking system. *A backend endpoint with no caller is not
half-finished work; it is invisible work: it looks done in review and does nothing for anybody.*

It lists **whole bookings**, where the day view lists **service items**. A cut at 11:00 plus a
colour at 11:45 is two rows on the timeline (two things, two times, two people's calendars) and
one row in history ("what did this customer have done"). Same data, two shapes, and the shape
follows the question — serving both from one shape would make one screen wrong.

Managers get it too, not just owners: *"when was this customer last in?"* is a front-desk
question. It takes no `salonId` — the salon comes from the JWT claim, so there is no parameter
to tamper with.

Also: `customerName` + masked `customerPhone` added to `BookingResponse` (so history shows a
person), and the customer's name is now the heading of both the schedule block and the booking
detail panel — when that panel is open, something has usually gone wrong with *this person's*
appointment, and who to speak to is the first thing needed.

Left open in `PENDING_WORK.md` §5b: **N3 reminders** (still the highest-value item),
**N4b rate-limiting reveals**, **N5 templated messages**, **N9 masked calling**.

### Session 36 (Darshan/Cowork) — one customer, at one salon

**`GET /bookings/salon/customer/{customerId}`** plus `CustomerHistorySheet`: tap a name in the
History list and see that person's visits, spend, cancellations, no-shows and usual stylist.

This did not exist in any form. `BookingRepository` had `findByCustomerId` (everything for a
person) and `findBySalonId` (everything at a salon) and **no combination** — so the
returning-customer view was unbuildable, and the obvious shortcut was for a screen to call
`findByCustomerId` and filter by salon in Java.

**That shortcut is the whole reason this session has a design.** It would have loaded *every
booking that customer has made anywhere on BMP* before the filter ran. Where a customer went
last month is another salon's commercial data and the customer's own business, and "we filtered
it in the UI" is not a defence once the JSON has crossed the wire. So the boundary is enforced
in three independent places:

- `findBySalonIdAndCustomerIdOrderByCreatedAtDesc` takes the pair, and there is deliberately
  **no single-argument variant on the salon side** — the constraint is structural, not a rule
  someone has to remember.
- The path is `/salon/customer/{id}`: the salon half comes from the JWT and is not in the URL,
  so the only salon a manager can name is their own.
- Identity (name, masked phone) is read from the **V006 booking snapshot**, not resolved live
  from bmp-user. The salon is entitled to what they were told at booking time, not to a live
  view of a person's current record. A salon-facing screen that queries bmp-user is one step
  from being a customer directory.

**404, not an empty summary,** when they have never booked here. A 200-with-zeros for any
well-formed UUID lets a salon test arbitrary ids and learn which ones are real BMP customers.

**Three smaller calls worth keeping:**

*Cancelled and no-show stay separate numbers.* They are different facts: cancelling is a
customer using the product correctly — the salon got the slot back and could resell it — while a
no-show cost them an empty chair. Merged into one "didn't attend" figure, a considerate customer
reads as an unreliable one. This is the number a receptionist acts on (prepayment?
double-book?), so it has to be right. The row is hidden entirely when both are zero, which is
the common case; a row of zeros on every regular trains people to stop reading it.

*"Usually sees ___" is null on a tie, and the line is then omitted.* Someone reads it out loud —
"you usually see Meera, shall I book her?" — so a preference invented from a two-way tie is
worse than saying nothing. Counted over **completed** items only: a cancelled booking says
nothing about who they like.

*The stats query is native SQL, not JPQL.* `final_amount_paise` maps through
`MoneyAttributeConverter`, and JPQL `sum()` over a converted attribute is not reliably supported
— Hibernate has to decide whether it's summing `Money` or `Long`, and the answer has changed
between versions. The column is a plain `BIGINT`, so SQL removes the ambiguity. The cost is
named in the code: it now knows schema and column names, and `status` is compared as text, which
is only safe because the entity is `@Enumerated(EnumType.STRING)`. That is the first thing to
check if these numbers ever read zero.

Also: only the **name** in a history row is the tap target, not the whole row. Tapping a row in
a list of bookings should plausibly open that booking; tapping a person's name unambiguously
means "tell me about this person", and surprise on a screen used for disputes is expensive.

Left open: **N10** — the same sheet from the Today tab's booking detail. "Have they no-showed
before?" is asked while the customer is at the door. `BookingDetail` has no `salonId` and
threading it through was more change than the win justified here.

### Session 37 (Darshan/Cowork) — cancel, reschedule, and terms that actually apply

Darshan asked for six things. **One of them worked.** Customer cancel did; salon cancel was
blocked in the state machine, reschedule didn't exist in any form, refunds ignored timing
entirely, and there were no per-salon reschedule settings at all.

**The finding that frames the session:** `free_cancel_hours` has existed since V002, was frozen
onto every booking by Session 30, and was **read by nothing**. A customer cancelling two minutes
before their appointment and one cancelling three weeks out got identical treatment — no fee, no
record, no difference. *Freezing terms nobody reads is worse than not freezing them, because it
looks solved.* `booking_modification` was the same shape: in the schema since V002, zero rows
ever written, and described in `BookingStatus`'s javadoc **wrongly** (it named
`scheduled_start/end` columns that don't exist on `booking` — the times live on the item).

**Decisions Darshan made, and what they cost to implement**

| Decision | Implementation |
|---|---|
| Reschedule keeps the ORIGINAL clock | `booking.original_start`, written once at creation, never moved |
| Salon may cancel, always full refund | `Transition(CANCELLED, Actor.SALON)`; `CancellationTerms` returns fee-free **before** consulting any band |
| Notice + limit + tiered fees + who-may-reschedule | Seven columns in V010, an owner-facing `PolicyPanel` |

**Why the original-clock rule is the load-bearing one.** Without it: book Saturday 11:00
(24h free window) → at 10:00 on the day, deep inside the fee window, reschedule to next month →
cancel "three weeks ahead", free. The salon lost Saturday's slot with an hour's notice and was
paid nothing. Every serious booking platform ties these together, and it is always the salon's
money that pays for getting it wrong. `reschedule_keeps_original_clock` defaults TRUE; a salon
opening it gets a WARN log naming the consequence.

**A real bug found while building reschedule.** The availability check made every short move
impossible: shifting a 60-minute service from 11:00 to 11:30 asks "is 11:30–12:30 free?", and
11:00–12:00 was occupied **by the booking being moved**. The item conflicted with itself. Fixed
by threading `excludeBookingId` through five files across two services
(`findBusyItemsForStylist` → `BookingAvailabilityService` → controller → `BookingServiceClient` →
`AvailabilityApi`), overloaded with a defaulted 4-arg form so the ordinary booking path is
untouched. Every *other* booking stays visible, so a reschedule still cannot land on someone else.

**Three smaller calls worth keeping**

- **The fee is WRITTEN, not computed on read.** `previewCancellation` runs the *identical*
  `CancellationTerms.forCancellation` call, so what the customer is shown and what is recorded
  cannot disagree. Recomputing later would also need "now" to be a past value, and would drift.
- **An unreadable snapshot charges nothing.** If we can't say what the customer agreed to, we
  can't claim they agreed to pay. The error goes in the log, not on the bill.
- **`cancellation_fee_reason` is words, not derivable from the bps.** A salon whose late fee is 0
  produces the same `0` as a free cancellation, and "you cancelled in good time" reads very
  differently from "we don't charge for late cancellations".

**UI:** `UpcomingPanel` (ordered by APPOINTMENT time — history orders by `created_at`, so a
booking taken yesterday for next month sorted above one taken last week for tomorrow),
salon-cancel with a required reason, `CancelFeeNotice` fetching the real figure on open (never
cached — a booking crosses a band while the sheet is open), and `PolicyPanel` for the owner with
**"changes apply to new bookings only"** stated above the controls.

Deliberately NOT built: salon-side reschedule *UI* (needs a slot picker against live
availability — a half-built one that double-books is worse than the phone call), and validation
that two items of the same booking aren't moved onto each other. Both in `PENDING_WORK.md`.

### Session 38 (Darshan/Cowork) — the reschedule UI, and a mock that hid a broken endpoint

Finished R5 and R6 from Session 37's register. Found a third thing on the way.

**`getSlots` has never worked against a real backend.**

It parsed the response as `SlotGroup[]` — `{label, slots[]}`, grouped into
Morning/Afternoon/Evening. bmp-salon returns a **flat** `List<SlotResponse(start, end,
stylistId)>`. A Zod parse of the real payload throws, so **the booking flow's slot picker would
have failed on its first live request** — the core screen of the whole product.

Nobody noticed because `USE_MOCKS` defaults ON and `mockSlots` returned the grouped shape the
client wanted. *The mock was written against the client's assumption instead of the server's
contract, so the two never had to agree* — which is precisely the failure Zod-at-the-boundary
exists to catch, defeated by a boundary the mock never crosses.

Grouping is a presentation decision and now happens client-side. `stylistId` was also being
discarded, and it matters: on the "any available" path it is the only way to know which stylist
a slot belongs to, which a reschedule has to send back. Hence `getRawSlots` alongside `getSlots`.

**`RescheduleSheet` — one component, both actors.** Customer and salon reschedule the same
booking against the same availability; the only differences are two props. Two components would
be two slot pickers, and the second one written would be the one that forgets
`excludeBookingId`.

Three decisions inside it:

- **A slot picker, not a time field.** Free text would let someone request 11:37 on a 15-minute
  grid. The server would refuse correctly, and the customer would have no idea which times *would*
  work. Every option shown is one the server will accept.
- **Multi-service bookings are moved one service at a time.** Auto-chaining ("the colour starts
  when the cut ends") sounds helpful and quietly produces a slot nobody checked — the second
  service's availability is a different question from the first's.
- **Changing the date clears every pick.** Those slot times belonged to the old date; keeping
  them would silently send yesterday's 11:00.

**Entry points are gated on the real answer, not guessed.** The customer's button asks
`reschedule-eligibility` first, and when refused shows *the salon's own wording* in its place —
"this salon needs 24 hours' notice… you can still cancel it" tells someone what to do next,
where a greyed-out button produces a support ticket. The salon's is gated on
`salonCanRescheduleDirectly`, fetched **once for the list** rather than per row, and when off the
row names the alternatives.

In both, **Move comes before Cancel**. For a stylist off sick, moving keeps the customer and the
revenue; cancelling loses both. Putting Cancel first makes the lossy option the default.

**R6 — `requireNoSelfOverlap`.** The `excludeBookingId` that makes rescheduling possible also
stops a booking's own items blocking each other, so a two-service booking could be moved onto one
overlapping time with every individual check passing — a customer in two chairs at once. Checked
after the availability pass and before any write. It refuses **same stylist only**: two services
in parallel with different stylists is a real arrangement (a manicure while a colour develops),
and refusing all overlap would break it.

### Session 39 (Darshan/Cowork) — the first tests, and CI stops skipping them

**46 tests across 4 files.** The first in this repository that assert anything about behaviour.

**Why these four, and not "some coverage".** The choice was deliberate: test the code where being
wrong is *silent, financial, and argued about with a real person*.

| File | Tests | What it protects |
|---|---:|---|
| `CancellationTermsTest` | 18 | What a customer is charged, from terms frozen months earlier |
| `BookingStatusTest` | 14 | Which moves are possible — mostly the ones that must stay impossible |
| `MoneyTest` | 9 | The rounding rule that multiplies everyone's income |
| `MaskPhoneTest` | 5 | The only thing between the salon desk and a customer list |

All four are pure logic — no Spring, no database, milliseconds to run. That is what made them
the right first tests rather than the easy ones.

**What the tests are actually protecting is decisions, not arithmetic.** The arithmetic in
`CancellationTerms` is four lines. The tests pin the sentences someone could "simplify" away in a
refactor without noticing:

- a salon cancellation is free **before** any policy is consulted — tested with the harshest
  possible policy (100%) and the worst possible timing (after the appointment), so moving the
  check below the band logic fails here
- an unreadable snapshot charges **nothing** — null, `"{}"`, and malformed JSON all tested. If we
  can't say what the customer agreed to, we can't claim they agreed to pay.
- the clock runs on the **original** appointment, so rescheduling can't buy back a free
  cancellation
- a pre-Session-37 snapshot charges nothing — old bookings behave exactly as they did before the
  feature existed
- band boundaries are inclusive at the generous end: exactly 24 hours out is free, not late

`BookingStatusTest` leans on negatives on purpose. Anyone adding a transition naturally tests
that it works; nobody thinks to check that the impossible things are still impossible. It loops
every terminal state × every target × every actor rather than spot-checking, and asserts that
only the SYSTEM confirms — the most tempting shortcut in the machine, since nothing reaches
CONFIRMED today.

**`MaskPhoneTest` exists for the bug that looks like success.** If masking ever returns its
input, nothing breaks, nothing logs, no other test fails — the numbers just appear, and keep
appearing.

**CI no longer skips tests.** `-DskipTests` has been in the workflow since Session 33, where the
comment said it was *"honest rather than aspirational"* because there were no tests. That was
true then and stopped being true this session — a comment explaining why something isn't done has
a short shelf life.

It couldn't just be deleted: every module ships a generated `*ApplicationTests` with a
`@SpringBootTest` context load, and those need PostgreSQL, Kafka and Eureka. On a runner they'd
fail for want of a database rather than for want of correctness, and **a red build caused by
missing infrastructure is the fastest way to teach a team to ignore red builds.** So they're
excluded by name in the root pom's surefire config, with the reason next to the exclusion and an
instruction to delete it once Testcontainers exists. `bmp-app`'s ArchUnit/Modulith rules still
run — they're static analysis and need no context.

**Verified without a JDK** by re-implementing `percentBps`, `maskPhone` and the fee bands
independently and running all 22 asserted values through both. Every one matched. Also checked
brace/paren balance, package-vs-path, text-block placeholder counts against `.formatted()` args,
and that `maskPhone` is package-private with the test in the same package — the method's
visibility was **not** widened to make it testable.

### Session 40 (Darshan/Cowork) — the customer journey had never worked

Darshan asked me to confirm four things were done. Three were. Checking the fourth found
something much larger.

**The entire customer discovery journey has never worked against a real backend.**

A sweep of every Zod schema against the backend record it parses found **four mismatches**, all
on the path: browse → salon page → pick a stylist → pick a slot. `NearbySalonResponse` was
`(id, name, distanceKm)`; `SalonSchema` demanded eight more fields, every one **required**. The
parse throws on the first real response. Only "Confirm" — wired in Session 28 — worked.

The fields mostly **did not exist in the database**. `salon` had seven columns: id, name, a
PostGIS point, status, assignment strategy, two timestamps. Nothing anybody would browse by.

**Why nobody found out, and why that's the real lesson.** `USE_MOCKS` defaults ON, and the mocks
returned exactly the shape the client wanted — because they were written from the *client's
assumptions* rather than the *server's contract*. **A mock that never has to agree with the
server guarantees the two will diverge, and hides it while they do.** Session 38 found the same
root cause in `getSlots`, and I treated it as a one-off. It wasn't.

**What was built**

| | |
|---|---|
| **V011** | `area`, `address`, `about`, `image_url`, `rating`, `review_count`, `salon_category` table, `salon_service.category`, `stylist.speciality` |
| `GET /salons/{id}/detail` | Everything the salon page needs in ONE call — the conversion funnel doesn't get three spinners |
| `GET /salons?category=` | The query the category child table exists for |
| FE | Schemas rewritten to the real shape; **every render path fixed for null** |

**Design calls worth keeping**

- **NULL rating ≠ 0.** A new salon shown as "0.0 ★" reads as terrible rather than new, and that
  cost lands on the salon least able to absorb it. `ratingLabel()` renders "New".
- **`startingPricePaise` is derived, not stored.** A stored copy goes stale the moment an owner
  edits a price.
- **`imageHue` and `topRated` moved OFF the wire.** A hue is not a fact about a business; a badge
  threshold is an editorial decision. Both are now computed in the app.
- **A child table for categories, not `TEXT[]`.** Postgres arrays need Hibernate's array
  JdbcType — an exotic type in a codebase with none — for a relation of five rows.
- **Fixing the schema was only half.** Permitting null and then calling `.toFixed(1)` on it trades
  a parse error for a crash. Six components were rewritten, and `MOCK_NEW_SALON` (a salon with
  *nothing* filled in) now sits in the mock list so those branches are exercised in development
  rather than by a real owner on their first day.

**Also closed: the salon was never told about a booking.** `booking.created` reached the customer
and nobody else — a salon only found out by having the desk open, which polls every 60 seconds.
A booking made overnight was invisible. `booking_notify_email` / `_phone` live on the **salon**,
not resolved from the owner's login: the owner is a person, the bookings inbox is a business
function that must survive that person leaving. The customer's phone number is deliberately
**not** in the alert — that stays behind the audited reveal, or every booking SMS becomes an
unaudited copy of a customer's number in someone's phone.

**`scripts/check-api-contracts.py`** — the sweep, committed and repeatable. Stdlib only, no node,
no maven. It found four more mismatches on its first run, including one my own edit had just
introduced, and one bug in itself (it couldn't see `.nullish()` inherited through `.extend()`).
**35 schemas now match.** It's a local script, not CI: the repos are separate and neither
workflow has the other checked out, so a CI job couldn't do this honestly.

Also fixed in passing: `toResponse` and `near()` threw on a salon with a malformed `location` —
and every salon created through signup gets placeholder coordinates (F3), so one bad row could
have 500'd the endpoint bmp-booking calls on **every single booking**.

Closes **F2** (signup collected `address` since Session 15 and discarded it).

### Session 41 (Darshan/Cowork) — finishing V011, and two open doors

Started by closing the loose end from Session 40 (signup collected `address`/`type` and still
didn't send them). Ended somewhere else.

**`PUT /api/v1/salons/{salonId}` had no `@PreAuthorize` at all — and accepted `status`.**

So **any logged-in user could approve their own salon**, skipping moderation and becoming
publicly bookable. They could also rename or relocate *any* salon by changing the id in the URL.
Two gates now: the salon's own OWNER (the `principal.salonId().equals(#salonId)` expression every
other salon-scoped endpoint uses), and `status` is rejected outright with a 403 — a salon
approving itself isn't an authorization bug needing a narrower role, it's a field that must not
be on the request.

**Then the sweep for siblings found something worse.**

`PUT /api/v1/reviews/{id}` was reachable **with no credential at all**. No `@PreAuthorize`, and
bmp-review's `public-paths` contains `/api/v1/reviews/*` — added so a review could be *read*
without logging in.

> **public-paths are path-only and method-blind.** The pattern doesn't say "GET", it says "this
> URL". A read that should be public silently publishes the write on the same path.

Session 29 recorded the lesson as *"a path is not a permission"* after finding an open
wallet-credit endpoint. This is the same lesson inverted, and the Session 29 sweep missed it
because that sweep looked for endpoints with *no* public path — this one *looked* deliberately
covered.

The salon-reply endpoints needed a login and nothing more, so **any customer could post a public
reply attributed to any salon**. Worse than editing a review: the salon can't see it happening
and the customer has no reason to doubt it.

**Fixing the annotation was only half.** `review` had no author column — nothing to compare a
caller against — so `hasRole('CUSTOMER')` would still have meant *any* customer edits *anyone's*
review, and it would have looked protected in review. V004 adds `author_user_id`, set from the
token at creation. Pre-V004 rows are refused rather than allowed: an unattributable review is one
nobody can prove they own, and defaulting to "allow" leaves the hole open for exactly the rows
most likely to be someone else's.

**New CI job: every write endpoint has `@PreAuthorize`.** POST/PUT/PATCH/DELETE, method or class
level. GETs are exempt — plenty are legitimately public and flagging them all would produce noise
that trains people to ignore the job. The exemption list is credential-establishing endpoints
only (login, OTP, TOTP challenge, activation, OAuth, webhooks), each with a note on what *does*
gate it, because adding to that list is adding an endpoint anyone on the internet can call.
**Verified green against the current tree.**

**Also shipped**

- **Signup sends everything now** — area, address, categories, and the owner's own email/phone as
  the salon's first booking-alert contact. Without that last one a new salon is never told about
  a booking, which is the failure mode most invisible from the salon's side.
- **`SalonProfilePanel`** — owners can edit their public identity and their alert contact. It
  warns loudly when no alert contact is set, and is honest about the two things it can't do
  (map pin, approval status).
- **`Chip` in `@ui`** — four screens had rolled their own and drifted on padding. Four copies is
  where a shared component stops being premature.
- The contract checker caught my own new `SalonAdminSchema` as unmapped on its first run after
  I added it. **36 schemas match.**

### Session 42 (Darshan/Cowork) — the backend finally ran, and why yours didn't

Darshan hit **Swagger errors and "some entities not created"** running locally. Root cause found
and reproduced against a real PostgreSQL.

**Maven can't run in the Cowork sandbox** — Maven Central is unreachable, there's no root to
install a JDK, and Lombok can't be fetched. But a pip-shipped PostgreSQL *can* run, and that
turned out to be enough to find the bug.

**The cause: `bmp-app` poisons the database, and the damage outlives the container.**

`docker-compose.yml`'s own header said *"the entire BMP backend is `docker compose up` + `mvn
spring-boot:run -pl bmp-app`"*. That instruction predates the Session 5 microservices pivot and
has been wrong ever since. `RUN_LOCALLY.md` §4 warns about it; the compose file itself did not.

What running it does, verified step by step:

1. bmp-app's migrations are frozen at the Session 5 monolith — **16 whole tables and 211 columns
   short** of today's schema.
2. They record themselves in the **default** `flyway_schema_history`. Every real service uses
   its own `flyway_schema_history_<svc>`, so each still believes it has migrated nothing.
3. Each service replays its own V001.. and immediately hits
   `ERROR: relation "salon" already exists`. Flyway aborts.
4. The service never finishes starting → `/swagger-ui` and `/v3/api-docs` error, and its tables
   stay stale → **"entities not created"**.

**8 of 8 services failed to boot** in the reproduction. And because `pgdata` is a named volume,
`docker compose down` keeps the damage — only `down -v` clears it.

**Fix:** `spring.flyway.enabled: false` in bmp-app, so a retired module can never lay a stale
schema over a good one; plus the compose header rewritten to say what to run and what not to.
**After the fix: all 9 services migrate cleanly on a fresh volume, and 0 entity columns are
missing** — `ddl-auto: validate` would pass.

**Verified for the first time, against a real database**

| Check | Result |
|---|---|
| All 45 service migrations, in order | **clean** |
| 578 `@Column` mappings across 72 entities | **every one resolves to a real column** |
| The 11 new CHECK constraints (fee bands, rating, categories) | **11/11 behave as documented** |
| All 365 Java files, against a real Java grammar | **parse clean** |

**A mistake worth recording.** My first migration run reported *"55 applied, 0 failed"*. It was
wrong: `psql()` writes errors to stderr and returns an empty string, so I read silence as
success while 14 statements were failing — including `CREATE TABLE salon`. I only caught it
because the entity check then showed every `Salon` column missing. The harness was rebuilt with
`ON_ERROR_STOP=1` and exit codes, **and self-tested against a deliberately broken query before
being trusted**.

I also asserted a wrong root cause first — that nine services shared one
`flyway_schema_history`. They don't; every service already sets `flyway.table`. My query simply
hadn't printed that column. Checking the actual config disproved it in one command.

**Still unverified:** everything only a compiler can tell you — signatures, generics, Lombok
accessors, Spring wiring, and the 46 unit tests. `mvn verify` remains outstanding.

---

### Session 42b — 100 build errors, one cause

Darshan ran the build. **100 errors** — which is javac's default `-Xmaxerrs`, so it stopped
counting rather than stopping there. Every one traced to a single root cause:

```
cannot find symbol: method getId()
location: variable b of type com.bmp.booking.entities.Booking
```

`Booking` is `@Getter` with per-field `@Setter`; its only hand-written methods are `touch()` and
`applyDiscount()`. So `getId`, `getBookingRef`, `getCustomerName`, `setOriginalStart` — **all of
them are Lombok's.** Checked all 15 reported-missing methods against the field list: every one is
Lombok-generated, none is a real missing method.

**Lombok wasn't running.** It was declared only as a `provided` dependency, leaving javac to
*discover* it as an annotation processor. That discovery is the fragile part — it depends on the
JDK, on `-proc` defaults (JDK 21+ warns; newer JDKs disable implicit processing outright), and on
whether the IDE delegates to Maven or compiles with its own settings.

**Fix:** an explicit `maven-compiler-plugin` `annotationProcessorPaths` entry in the root pom, at
`${lombok.version}` so it stays pinned to what Boot 3.4.1 was tested against. No discovery step,
identical behaviour under `mvn`, IntelliJ-delegated builds, and CI. **39 classes across 12
modules** use Lombok and would all have failed the same way.

**Why it looked like a hundred unrelated problems.** Once javac has that many errors it reports
downstream symbols as unresolved too — hence the log also claiming `SlotLock`, `BookingEvents`,
`BookingEventsRepository` and `com.bmp.booking.client.dto` were missing. All four are present, in
the correct packages (verified: 347 files, zero package-vs-path mismatches). They were collateral,
and chasing them would have been chasing smoke.

Also fixed, the one real warning: `@Deprecated` on three `ItemRequest` record components. javac
was right that it "has no effect" — `@Deprecated`'s `@Target` doesn't include `RECORD_COMPONENT`,
so it was silently attaching to the constructor parameter, where it warns nobody. Removed rather
than suppressed: those fields are deliberately *accepted and ignored*, so warning every caller
would be noise, and the javadoc plus `BookingService.create` already say so. **A mechanism that
looks like it warns and doesn't is worse than plain prose.**

**If it still fails in IntelliJ**, IntelliJ is using its own builder rather than Maven:
Settings → Build → Compiler → Annotation Processors → *Enable annotation processing*, and
Build Tools → Maven → Runner → *Delegate IDE build to Maven*. Also check the Project SDK is
**21** — Lombok 1.18.36 predates JDK 24/25 and fails on them however it's configured.

---

## How to Add to This File

When you finish a session:
1. Add a new `### Session X` heading to [Full Session Log](#full-session-log--every-chat-turn-summarised)
2. Update the status tables at the top with ✅ or 🔜
3. List the commits pushed (or link them)
4. Flag any team ratification needed (like the Session 5 microservices decision)
5. DO NOT change [Locked Decisions](#locked-product-decisions) — raise a PR for debate if needed
