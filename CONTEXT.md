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

**`ApiContractTest`** (was `scripts/check-api-contracts.py` until Session 43) — the sweep,
committed and repeatable. Runs under `mvn verify`, no extra toolchain,
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

### Session 42c — `NoClassDefFoundError: com/bmp/common/ids/UuidV7` on login

Reported from the web login modal: `Handler dispatch failed: java.lang.NoClassDefFoundError:
com/bmp/common/ids/UuidV7`. Nothing was wrong with the code.

**What the evidence showed.** `UuidV7.class` was present in every place it should be — source, in
`bmp-common/target/classes` (19:28:30), in the installed jar, and inside bmp-auth's nested
`BOOT-INF/lib/bmp-common-0.1.0-SNAPSHOT.jar`. Build order was correct (common 19:28:30 → auth
19:29:44). But an *earlier* build had failed at 19:14, leaving `bmp-common/target/classes` partial,
and the running `bmp-auth` JVM had been started somewhere in between. It kept that stale view.

**Two things worth internalising.**

1. `mvn -pl <service> spring-boot:run` resolves `bmp-common` from your local **`.m2`**, not from the
   sibling folder. `package` doesn't touch `.m2`; only `install` does. So a service can start
   against a copy of common that is weeks old, and `bmp-common` has changed in five recent sessions
   — four domain events, a security filter, `Money`.
2. **Classes resolve lazily.** The service starts, serves most traffic, and throws on the *first
   request that touches the new class*. So a rebuild alone is not enough — anything already running
   must be restarted. The failure surfaces far from its cause in both space and time, which is why
   it reads as a code bug.

**Reading the message.** The slash-separated `com/bmp/common/ids/UuidV7` is the classloader's
"not found" form. *"Could not initialize class com.bmp.common.ids.UuidV7"* (dots) is a different
failure — the class was found but its static initialiser threw. Same-looking log line, unrelated
fix; distinguishing them is the whole diagnosis.

Documented in `RUN_LOCALLY.md` §2b (a new subsection under the build command, since §2 said *what*
to run but not *why* or that it's needed **again** after every `bmp-common` change) plus a §12
Troubleshooting row. No code change.

### Session 43 — the login audit, and five things it turned up

Started from one symptom: signing in as the seeded salon owner returned *"email is required to
sign up"*. Ended up rewriting how auth answers the question **"does this account exist?"**

**1. `lookupUserByPhone` was `catch (Exception ignored) { return null; }`.**
That line made an *outage indistinguishable from a fact*. bmp-user down, a wrong
`X-Internal-Service-Key` (the endpoint is `hasRole('SERVICE')`, so a bad key is a 403), no Eureka
instance, a socket timeout — all of them returned "no such user", and the caller confidently took
the SIGNUP branch for someone who'd had an account for months. The visible cost was the confusing
message. The real cost was one small edit away: if the request *had* carried an email, we'd have
sailed past the guard and created a **second account on a phone that already had one**, orphaning
its bookings and salon. Only the unique index on `users.phone` stood in the way, and a uniqueness
constraint catching a logic error is luck, not design. Now: **only a 404 means "no such user"**;
everything else is a 503.

**2. `resolveSalonScope` had the same shape, and was worse.** It failed *silently*. With bmp-salon
unreachable, an owner got a token carrying `salonId = null`, logged in "successfully", and then hit
403 on every panel — because every salon-scoped `@PreAuthorize` reads
`principal.salonId().equals(#salonId)`. They'd report "the console is broken", and nobody would
think to look at bmp-salon. Now it refuses to mint the token.

> Worth recording the near-miss: my first version of that fix looked for
> `FeignException.NotFound`. bmp-salon actually signals "no seat yet" with **204 No Content** —
> `.orElseGet(() -> noContent())`. Shipping it would have 503'd every fresh salon owner who
> hadn't created their salon. Caught by reading `StaffController`, not by reasoning about it.
> **Check the callee's contract; don't infer it from the caller's error handling.**

**3. The phone field invited the bug it then rejected.** `phoneSchema` is `/^\+[1-9]\d{7,14}$/` —
the *wire* format, which must accept every country. The form renders a fixed `+91` chip, so typing
`919876500003` built `+91919876500003`, passed validation, and matched nothing. New
`indianMobileSchema` validates the local part as exactly 10 digits starting 6–9, and
`normaliseIndianMobile` strips a leading `91`/`0` first. **A validator borrowed from the wire
format won't catch input errors the UI itself invites.** Fixed in one file; all six doors share it,
including the invite panels where a mistyped number means an invite nobody can redeem.

**4. OTPs were replayable.** No `consumed_at`, and the row was never invalidated — a verified code
kept working for the rest of its 5-minute TTL, for anyone holding it, and email is the only live
channel. V005 adds the column plus a partial index on live rows; `markConsumed()` is deliberately
not a setter (the only legitimate transition is unused → used, once) and is idempotent so a replay
can't overwrite the record of the first use. `OtpConsumptionTest` asserts, among other things, that
`setConsumedAt` **does not exist** — the test's real subject is the absence of a method.

**5. `000000` unlocked every account.** Convenient until the first real user exists, and it meant
the real path (generate → email → read → type) was never exercised locally. Now restricted to the
six seeded phones via `dev-master-otp-phones`, and **AuthService refuses to start** if the master
OTP is set with an empty allowlist. Any other number gets a real emailed code.

**Also: the staff door could mint a customer account.** `requestedRole` defaults to `customer` and
StaffAccessSheet's sign-in path sends no role — a typo'd digit would have created a customer and
then shown "Not a staff account". It only failed safe *by accident*, because that call happens not
to send an email. Added `loginOnly` to the verify contract: **when behaviour depends on intent,
send the intent** rather than deducing it from which optional fields happen to be absent.

**Channels.** `WhatsAppSender` is now its own interface, not a flavour of `SmsSender` — WhatsApp
needs a pre-approved *template name*, not free text, reports delivery asynchronously by webhook,
and fails permanently (not transiently) when a number has no WhatsApp account. Collapsing them
would have meant rewriting every call site later. Both stubs now honour their config flag and are
off by default; a flag a stub ignores misinforms the reader about what the system does.
**Email remains the only live channel** and is dispatched first in `handleOtpRequested`, with a
loud `log.error` if an OTP has no email address — that's a dead end for the user.

**Found while verifying: BMP-FE had not typechecked since Session 41.** Ten errors, none of them
mine — `getSalon`/`getSalonAdmin`/`updateSalonProfile` existed in `salons.ts` but were never added
to the `@api` barrel, so `SalonProfilePanel` couldn't compile; `SmartImage.uri` rejected the `null`
its callers are contractually fed; `ServicePicker` used a nullish `category` as a Map key, and
`ServiceSchema`'s own comment already promised the "Other" grouping that was never written. Fixed;
`tsc --noEmit` now exits 0.

**The seed had silently stopped working.** Loading it failed on `uk_users_phone`, because rows
created by the two bugs above were squatting seeded phone numbers with random ids — and the seed's
guard is `ON CONFLICT (id)`, which never matched. Since the file is one transaction, the whole
thing rolled back: *one stale row, and the seed does nothing at all*.

Fixing that exposed two more, which the users error had been masking all along: the seed inserts
`salon_service.updated_at` and `salon_staff.status`, and **neither column has ever existed**. The
`salon_staff` rows are the ones that make owner login work — `resolveSalonScope()` reads that table
on every token mint, so without them Kavya authenticates and then gets 403 everywhere.

Three compounding reasons nobody noticed: the single transaction means the first error hides every
later one; the file only ever runs by hand, after a reset, when someone is already debugging
something else — so its errors get attributed to that; and `seed/README.md` asserted idempotency,
which was true only against re-running itself. **Demo data that breaks only when you need it is
worse than data that breaks loudly**, because you reach for it precisely when you're already lost.

Now: a reclaim step deletes squatters on seeded phones (children first — real FKs), scoped so it
can only ever match the six seeded numbers, leaving real signups alone. Plus
`SeedSchemaTest` in bmp-common, which parses the migrations statically (no Docker, under a
second) and fails on any INSERT naming a column that doesn't exist. **Self-tested against a
deliberately broken seed before being trusted** — the habit I most want to keep from Session 42.

**Verified, not assumed:** the seed now runs three times consecutively, clean, starting from a
database pre-loaded with squatter rows, against a schema built from all 9 services' migrations —
8 salons, 55 services, 3 staff seats, Kavya wired to Lumière as OWNER. Also: all 5 bmp-auth
migrations applied against a real PostgreSQL (`consumed_at`
nullable, partial index present, inserts default to unused); 8 changed Java files parse clean;
contract checker still reports 36/36; `tsc --noEmit` clean. `mvn verify` still hasn't run here —
Maven Central is unreachable from this sandbox, so the compiler remains the one check nobody has
performed.

### Session 43b — the repo guards are JUnit tests now, and there is no Python left

Darshan: *"i dont want any .py python files."* Fair, and it turned out to be the better design.

Four repo-wide invariants lived outside the build: two scripts under `scripts/` and two inline
`python3` heredocs in `ci.yml` (plus two more pasted into `docs/API_ACCESS.md` as instructions).
They are now tests in `bmp-common/src/test/java/com/bmp/common/repo/`:

| Test | Was | Checks |
|---|---|---|
| `PublicPathsTest` | ci.yml heredoc | every service declares `public-paths`, none sets `/**` |
| `WriteEndpointAuthTest` | ci.yml heredoc | every write endpoint carries `@PreAuthorize` |
| `SeedSchemaTest` | `check-seed-columns.py` | every seed INSERT names columns that exist |
| `ApiContractTest` | `check-api-contracts.py` | every Zod schema matches its backend record |

**Why this is an improvement, not just compliance.** They now run under the `mvn verify` everyone
already types — so they run *locally, in the IDE, before the push*. A check that only lives in CI
tells you what you broke after it's too late to fix quietly. And a check pasted into a document
(`API_ACCESS.md` §9 had two) only runs when someone remembers it exists: **a check you have to
remember to run is a check that stops being run.** It also drops a whole toolchain — Python and
PyYAML — from a repo that otherwise contains only Java and TypeScript.

The cost, stated honestly: these tests read the source tree, so moving a folder breaks them. That
is deliberate. Each encodes a rule learned from a real incident, and every one asserts it actually
found something to check (`isPositive()` on the count) — because a scanner whose glob stops
matching passes forever while checking nothing, which is the failure mode a repo-scanning test is
most prone to.

`ci.yml` lost both heredoc jobs; only `migration-hygiene` remains alongside `build`.

**Verified without a compiler.** Maven can't run in this sandbox (Central unreachable), so I
reimplemented all four algorithms in Node against the real tree and confirmed they reproduce the
originals exactly — including the same **36 schemas matched**, 9 seed inserts, 9 services, 31
controllers, all green. Plus: 5/5 files parse clean, no missing imports, no unused imports.
**The Java is unverified by javac** — that remains true of the whole repo and is the one gap
`mvn verify` on your machine would close.

---

### Sessions 44–49 — the surface an owner, a stylist and a customer actually touch

Six sessions of feature work, and one bug class that appeared in all of them.

#### The recurring lesson: a screen that works against mocks proves nothing

Four separate times a feature looked finished and had never once succeeded against a real
backend. Each was found by asking "what is this actually connected to?" rather than by reading
the code, which was correct every time.

| What looked fine | What was actually true |
|---|---|
| Admin console showing salon requests | `BMP-ADMIN` had no `.env`, and `VITE_USE_MOCKS !== 'false'` defaults mocks ON |
| Owner never notified of new requests | `ops-email` defaulted to the empty string |
| Stylist's Today tab | `/bookings/salon/day` is owner/manager-only — every stylist got 403 |
| Stylist's "not in today" toggle | Salon-scoped guard needs `principal.salonId()`, which a stylist's JWT never has |

**Rule to keep:** when a screen shows plausible data that doesn't match reality, check what it is
connected to before you check the code.

#### Session 44–47 — discovery, moderation, closures, money surfaces

V011–V019: salon discovery fields, pincode and free-text area, the `salon_reference` sequence
(BMPS001), the split of `approved` from `active` with an explicit **Go Live**, and salon closures
that the availability algorithm subtracts. Commission stopped being settable by the owner — 
`POST /salons/{id}/policy` is owner-guarded and the request body carried `commissionBps`, so an
owner could set their own platform rate to zero with one curl. **Authorise the path, then trust
the body** is the hole this codebase keeps re-finding.

#### Session 48 — a stylist owns their profile

V020–V021. A stylist can register themselves ("I'm a stylist" on the customer account screen),
search for the salon they work at, and ask to be added. **The owner's acceptance is the only
thing that creates a `stylist_salon` row** — verified as a single call site, because anything
else means anyone can put themselves on any salon's public page by typing a name into a form.

V021 enforces **one active salon per stylist** with a partial unique index rather than a service
check, because the service checks are read-then-write and two owners accepting the same stylist
simultaneously both pass. Alumni rows are unlimited: that list is the stylist's work history and
is what survives them changing jobs.

**Two real bugs found on the way.** `stylist_rating` was a nullable column mapped to a Java
primitive, so `ReviewService` stored **0** whenever a customer skipped rating the stylist. Dormant
because nothing read it back — until stylists could see their own reviews, at which point a
stylist whose true average was 4.67 read as **2.80**. V005 nulls the zeros and adds a CHECK. And
`markAlumni` left a departed stylist flagged bookable and overwrote `left_at` on a second press.

#### Session 49 — the booking window, leave, receipts, and a calendar

- **V022 — the booking window.** `booking_horizon_days` (how far ahead the diary opens) and
  `min_notice_minutes`. Notice **defaults to 0** on Darshan's explicit instruction: *"any person
  needs urgent hair cut he had some function he need wait for 2 hours its not good"*. A platform
  should not impose a wait; a colourist who needs prep sets their own. Both enforced in
  `AvailabilityService.freeSlots`, which booking creation already validates against — one
  enforcement point covers the picker and the booking.
- **V023 — leave.** The mechanism already existed: `stylist_availability` has carried
  `rule_type='leave'` since V003 and the algorithm has always subtracted it. What was missing was
  anything that could create the row. **Approval writes the rows**, which is why leave approved a
  month early takes effect on the day with no scheduled job. A pending request blocks nothing —
  blocking on request means a request the owner would have declined has already cost the salon a
  day of trade.
- **V024/V008 — invoices.** One document per booking, sequence-allocated number, line items
  **copied** at issue so renaming a service never rewrites last month's bills. Reads *amount due*
  until payment is recorded, then becomes a receipt with the same number. Honest by design: no
  payment gateway exists, and a receipt for money nobody received is the worst possible bug here.
- **`DayTimeline`** — the owner/manager day as a real calendar, one column per stylist. A list
  cannot answer "who's free at 4", because two appointments an hour apart and two back-to-back
  look identical in it. No drag-to-reschedule: that would skip the availability, notice and fee
  checks `RescheduleSheet` exists to run.

#### Session 49b — the gateway was swallowing six paths

`GatewayRouteTest` asked "is this prefix routed *somewhere*?" and could not fail when a path was
routed to the **wrong** service. A new ordering test asks, for each real path, which route matches
FIRST — the gateway's actual rule — and found:

- `/api/v1/salons/*/reviews` → went to bmp-salon, which has no such handler. Broken since the
  endpoint was written; unnoticed because nothing called it.
- `/api/v1/stylist-profile/**` → **no route at all.** Every Session 48/49 stylist endpoint 404'd
  at the gateway. The whole self-service feature was unreachable in a real deployment while
  working perfectly against the service directly. It is not covered by `/api/v1/stylists/**`:
  Ant treats `-` as an ordinary character, so the two share a prefix and nothing else — the same
  trap as `/api/v1/coupons/**` vs `/api/v1/coupon-requests/**` in Session 44.
- The two Session 49 invoice paths, salon-shaped but served by bmp-booking, would have joined
  them on the first real call.

**A SALON-SHAPED PATH DOES NOT IMPLY bmp-salon**, and route order is load-bearing.

#### Still open

- **`mvn verify` has never run in the assistant's sandbox** (Maven Central unreachable). Every
  claim here rests on tree-sitter parses, record-arity checks, real-Postgres migration runs and
  reimplemented algorithms. **javac and the IDE remain the real check** — and IntelliJ has caught
  at least one thing these did not.
- No payment gateway: every booking sits PENDING and invoices only become receipts when a salon
  records a counter payment.
- MinIO orphan-object sweep.

---

### Sessions 50–51 — payments connected, and two money bugs that were worse than the gap

#### Session 50 — booking → payment → webhook → confirmed

bmp-booking and bmp-payment had **never been connected in either direction**. That is why every
booking BMP has ever taken sits PENDING: `BookingStatus` has reserved `PENDING → CONFIRMED` for
`Actor.SYSTEM` with the comment *"Razorpay webhook ONLY"* since the enum was written, and no
webhook existed to perform it.

The chain now runs: booking opens a payment order (commission frozen) → gateway → signed webhook
→ capture + commission ledger → `payment.captured` → booking CONFIRMED + invoice becomes a
receipt.

**The gateway is behind an interface.** Everything that matters — freezing the split,
deduplicating a redelivery, confirming the booking, writing the ledger — is BMP's logic, and
wiring two HTTP calls directly in would have meant none of it could be tested without a Razorpay
account and a real card. `FakePaymentGateway` needs three independent things true before it loads
(explicit property, not-prod profile, warns on every call), because **a system that silently
pretends payments succeeded is worse than one that cannot take payments at all.** Razorpay's
signature verification and payload parsing are real; its two HTTP calls throw rather than
returning a fabricated order id that would fail later, at the salon door.

**Two money bugs found on the way, both worse than the gap.**

1. **Commission ignored the salon's negotiated rate.** `PaymentOrderService` had
   `COMMISSION_BPS = 1200` and applied 12% to every order — while Session 48 gave each salon its
   own `salon_policy.commission_bps`, set by an admin at approval, specifically so rates could be
   negotiated. BMP could agree 8%, store 8%, show 8% in the console, and charge 12%. On a ₹800
   booking that is **₹32 short to the salon, every time**, invisible until a partner audits a
   statement.

2. **Every uniqueness guarantee in the payment schema was fictional.** V002 documents four columns
   as `UNIQUE`, including `webhook_event.razorpay_event_id` with the comment *"UK — dedup at DB
   level"*. Not one had a unique index; that column got a plain `CREATE INDEX`. Only the ten
   primary keys existed.

   This matters because `WebhookService` was written around it: *"the UNIQUE index is what
   actually prevents double-application"*. Under concurrent redelivery — normal, gateways deliver
   at-least-once — both requests pass the `exists()` check, both capture, and **the commission
   ledger is credited twice for one payment.** Found by a test that inserted the same event id
   twice and expected failure; it succeeded.

   **A comment claiming a guarantee is worse than no comment, because code gets built on it.**

Also: bmp-payment had no `@EnableScheduling` and no outbox relay flag — the fourth service to
silently opt out of that per-service switch. `payment.captured` would have been committed and
then sat in the table forever, with the customer's money taken, the ledger crediting BMP, and the
booking still reading "awaiting payment".

#### Session 51 — who can remove a stylist

Three levels, each meaning something different:

| Actor | Action | Meaning |
|---|---|---|
| Owner **or manager** | Remove from team | Employment. They work elsewhere tomorrow. |
| Admin | Remove from a salon | The same act, for when a salon can't or won't. |
| Admin | Suspend | The platform barring them from BMP entirely. |

**Managers were excluded, and the reason had expired.** The javadoc said: *"irreversibility is
why… There is no un-alumni endpoint."* Session 48 made re-adding a stylist reuse their old row —
status flips back, `left_at` clears, rating and review count survive — so removal became one tap
to undo. The guard outlived its justification by three sessions.

> **A guard justified by a limitation has to be revisited when the limitation goes.** This one
> survived because the reason lived in a comment nobody re-read while changing the thing it
> described.

**V025 — suspension.** Lives on `stylist`, not `stylist_salon`, because it is a fact about the
PERSON: a suspended stylist with no current salon must still be suspended when they later ask to
join one. Enforced in **four** places — invite redemption, owner add, join-request acceptance, and
availability — via a shared `StylistSuspensionGuard`, because a suspension enforced in three of
four is not a suspension. Availability had to be one of them: without it, somebody suspended today
would keep taking bookings at the salon they were already on.

Two deliberate restraints: suspending does **not** rewrite `stylist_salon` rows (a salon keeps an
honest record of an employment that really happened), and the salon is told the stylist is
unavailable but **not why** — the reason may reference a safety complaint, and the salon is a
third party. The stylist gets it in full, by email and on their own profile.

**A bug in my own design, caught by a test.** The first `reinstate()` cleared `suspended_at`,
which erased when the bar started *and* violated V025's own CHECK requiring a reinstatement to
point at a suspension. Fixed: both timestamps persist and "currently barred" is the pair.

#### Session 52 — the counter takes bookings, and the picker got fast

Two things Darshan named, both about the same screen.

**1. Walk-in and phone trade was anonymous.** The desk's only tool was `POST /availability/walk-in`,
which writes a `walk_in_block`: the stylist's time vanishes from the calendar and *nothing else* is
recorded — no customer, no services, no price, no invoice, no history. For most salons that is the
majority of the business, and the platform could not see any of it.

Darshan: *"suppose any customer calls the manager or comes to walk in, then the manager should
update in the portal and the manager should select the stylist… we should compulsory to have their
data in our database who are booking through salon **remember its there own customer**."*

That last clause shaped the schema. **`salon_schema.salon_customer` (V026) is NOT a BMP account.**
Somebody who read their number to a receptionist has not signed up to BMP and must not be
quietly acquired as a user. It is the salon's private contact card, unique on `(salon_id, phone)`
so the second visit recognises the first. `linked_user_id` stays null until that person signs up
themselves — deliberately never populated by a background phone-number matcher, because Indian
mobile numbers get recycled and merging a stranger's visits into somebody's account cannot be undone.

`booking_schema` V009 makes `customer_id` nullable and adds `source` ('online' | 'counter'),
`salon_customer_id` and `taken_by_staff_id`, with a CHECK enforcing **exactly one** identity per
booking. Verified against real Postgres: 15 constraint cases, and three legacy rows seeded *before*
the migration survive it as `source='online'`.

Counter bookings go down the **same** `createInternal` path an app booking uses — same price
resolution from the salon's menu, same slot validation, same policy snapshot, same invoice. A second
`createCounter` that reimplemented any of that would be two answers to "is this slot free?" and
"what does this cost?". They differ only in a `BookingIdentity` record. Two deliberate differences
in behaviour: they are **CONFIRMED on creation** (there is no payment webhook coming for a walk-in,
and Session 50's bug was every booking sitting PENDING forever), and they **raise the invoice
immediately** rather than on payment capture.

Ordering matters and is documented in `CounterBookingService`: the customer record is written
**first** (so a booking without one is impossible, not merely discouraged), the booking second, and
the visit count **last** — counting first would inflate a regular's loyalty every time a
receptionist started a booking and abandoned it.

**2. "The UI for stylist available is a bit laggy… make it fast and attractive."**

Root cause: `freeSlotsAnyStylist` looped over the team calling `freeSlots` per stylist, and *each*
of those made its own cross-service HTTP call to bmp-booking. Six stylists = six sequential round
trips and roughly forty queries to answer one "who's free today?". The app made it worse by calling
`/availability/slots` once per stylist too, so the list visibly reshuffled as responses landed out
of order.

Now: `GET /api/v1/availability/salon-day` — salon policy, opening hours, closures and the notice
cutoff read **once**; every stylist's busy windows in **one** batched call
(`/bookings/internal/busy-windows/salon`, two queries total); the per-stylist interval arithmetic
done in memory. Names, specialities and ratings travel *with* the slots, so nothing is joined on the
device and there is no flash of "Stylist 4f2a…". `freeSlotsAnyStylist` now flattens the batched
result rather than keeping a second implementation.

A stylist with a full day comes back `busy: true` with an empty list rather than being omitted —
"Anjali is fully booked" is a useful answer at the counter and a missing name looks like a bug. If
bmp-booking is unreachable the method returns **no** availability and logs at ERROR, because
returning "everything is free" would double-book people.

**The `-` trap, for the sixth time in this repo.** `/api/v1/salons/**` does *not* match
`/api/v1/salon-customers/...` — Ant treats `-` as an ordinary character. Same class as
`stylist-profile` (Session 49) and `coupon-requests` (Session 44). Added explicitly to the gateway
predicate and to `GatewayRouteTest`.

Rewards/referral payout stays deferred at Darshan's request.

#### Session 53 — the customer book you can actually read, and the stylist finally gets told

Two gaps found by auditing the four roles rather than trusting the task list.

**1. Session 52 wrote customer records nobody could look at.** Recording a walk-in customer became
mandatory, which was the requirement — and the salon's only way to SEE that data was a search box
buried inside the booking sheet. A database the owner cannot read is one that quietly stops being
trusted, and the desk goes back to the paper diary.

New **Customers tab** on the desk (owner AND manager, matching the endpoints): browse regulars most
recently seen first, search by partial name or phone, open somebody to read and edit their note,
and see every visit they have made here. Backed by `PUT /salon-customers/{id}` and
`GET /bookings/salon/counter-customer/{id}`.

Two deliberate omissions, both documented on the screen rather than left as dead controls:

- **The phone number is not editable.** It is the unique key `(salon_id, phone)` that the whole
  visit history was matched by; editing it in place would either collide with another record or
  silently re-point one person's visits at another. A wrong number becomes a new customer.
- **Nothing can be deleted.** `salon_customer_id` on `booking` has no FK (it crosses services), so
  a delete orphans real bookings and surfaces months later as a receipt with no name. When it is
  wanted it should be a deliberate anonymise.

The visits endpoint is deliberately NOT `customerAtSalon`: that builds its rich card from the
`customerStats` projection, which is keyed on `customer_id` — a counter customer has none, and
their bookings carry `customer_id = NULL` (V009). Widening that projection would give it two
meanings depending on which id happened to be populated. The counts a receptionist actually wants
are already denormalised onto `salon_customer`.

**2. The stylist was the only party never told they'd been booked.** The customer got a
confirmation (Session 34), the salon got an alert (Session 40); the person who has to be standing
at the chair found out by opening the app — including for a counter booking taken ten minutes
beforehand, which is exactly when a message is worth most.

New `StylistAppointmentChanged` event, one per affected stylist (a booking can span several
diaries), covering **booked / moved / cancelled**. Emitted from create, reschedule and cancel;
every lookup is wrapped so a failed name resolution can never fail a real booking.

**The boundary is enforced by the record's SHAPE, not by the handler's restraint.** The event has
no field for the customer's name, phone, email or id, and none for the price. A template somebody
writes next year cannot leak a field that was never carried. Same Session 48/49 rule that
`ScheduleEntryResponse` follows, made structural.

**A bug caught while writing it.** The first version of the "moved from" line read `previousStart`
from `existing` *after* the write pass. But `existing` holds managed JPA entities and the write
mutates them in place (`item.setServiceStart(...)` on objects that came out of that very list), so
the message would have read "moved from 4pm to 4pm" — emitted, technically correct code, entirely
useless, and the kind of thing nobody notices until a stylist asks why the email says nothing
changed. The capture now happens beside the existing `before_snapshot`, which is the one place that
was already provably pre-mutation.

**A false alarm worth recording.** An ad-hoc gateway-route simulator reported
`/api/v1/salon-customers` as unrouted. The simulator was wrong, not the config: both Spring's
`PathPattern` and `GatewayRouteTest`'s own matcher treat a trailing `/**` as matching the bare path.
Checked against the test's matcher before changing anything — the lesson being that a
reimplemented matcher is only as trustworthy as the one it is reimplementing.

Verified: 426 files parse, 34 migrations apply against real Postgres, 18 event construction sites
match their record arities (comment-stripped — the naive check false-positived on commas inside
javadoc), `tsc --noEmit` clean, every new endpoint carries `@PreAuthorize`, and the new event
provably carries no customer or money field.

#### Session 54 — anyone could review anything, and nobody could review at all

Found by auditing TODOs rather than trusting the backlog. `ReviewService.create` carried this,
from the CRUD-first build order:

> `// TODO(Phase 3 / inter-service): call bmp-booking-service to confirm booking.status ==`
> `// COMPLETED before allowing a review. Skipped in this CRUD-first pass.`

**THREE faults, stacked, each hiding the others.**

1. **No verification.** With `hasRole('CUSTOMER')` as the only gate, any logged-in account could
   POST a review against any booking id — one belonging to somebody else, or one that never
   existed. The sole defence was one review per booking id, which stops a second fake review and
   not a first. A competitor could one-star a salon; a salon could five-star itself.
2. **The client supplied `salonId` and `stylistId`.** So even a genuine customer reviewing a
   genuine appointment could attach it to a salon they never visited, or credit a stylist who was
   never in the room. Same class as the Session 30 price bug.
3. **The gateway route was wrong — the SEVENTH instance of that bug class.**
   `POST /api/v1/bookings/{id}/review` is declared in bmp-**review**, and `/api/v1/bookings/**`
   sent it to bmp-booking, which has no such handler. It 404'd.

And a fourth thing that explains why none of it was noticed: **there was no frontend at all.** No
customer could reach the endpoint. A feature unreachable from both ends is indistinguishable from
one that hasn't shipped, so nobody audited its guards, and the broken route was never exercised.

**Severity had grown quietly.** These were written when reviews were decoration. Session 52 made
stylist ratings order the counter's availability picker — so invented reviews now change who gets
offered work.

**The fix, end to end.** bmp-review had no outbound clients whatsoever, which is precisely why the
TODO survived eleven sessions: there was no way to ask. It now has openfeign, a
`FeignInternalKeyConfig` and one client. `requireReviewable` refuses unless the booking exists
(404), is the caller's (403), is not a counter booking (403 — no account could have written it),
is COMPLETED (409) and finished within 90 days (409). Salon and stylist come from the BOOKING; the
request's copies are ignored, and a stylist rating naming somebody who never worked on it is
dropped while the salon rating is kept — losing a real review to a stale client-side id is the
worse outcome.

**bmp-booking unreachable REFUSES the review (503).** Deliberately the opposite of the
contact-snapshot rule: a missing confirmation email costs nothing recoverable, whereas an
unverified review is permanent, public and moves a rating. Failing open would silently restore the
exact hole every time bmp-booking restarted.

Frontend: `ReviewSheet` on completed bookings only, asking whether a review already exists before
offering the form, separate optional stylist stars (skipping stores NULL, not the zero that made a
4.67 stylist read as 2.80 in Session 48), and the server's refusals surfaced verbatim because they
are written for the customer.

Verified: 13 abuse cases reimplemented and all refuse correctly — including "bmp-booking down must
not fail open" and "a review naming a competitor's salon is stored against the real one". 428 files
parse, 34 migrations apply, `tsc` clean, gateway resolution asserted for the new route plus three
control paths that must still reach bmp-booking.

#### Session 55 — the coupon audit: two validators, and a rule nothing enforced

Darshan asked for seven things. Auditing first showed **five were already built** — owner raises a
request scoped to their own salon (forced server-side from the token), support/ops issue directly
under `CouponIssuePolicy`, `coupon_user` targets named individuals or a group, `max_discount_paise`
gives "20% up to ₹100", and the escalation message already says an admin can issue more.

Three things were genuinely wrong.

**1. TWO live implementations of "is this coupon valid".** `POST /coupons/quote` runs
`CouponRedemptionService` — audience targeting, the percent cap, the first-booking rule, all of it.
`POST /coupons/validate` ran `RewardsService.validate`: six rules, **no** audience check (a coupon
issued to three named people validated for everyone), **no** max-discount cap ("20% up to ₹100"
previewed as an uncapped 20%), and the welcome-coupon first-booking rule carrying the comment
*"skipped (assumed true) in this CRUD-first pass"*.

The app has always called the good one, so this looked harmless. It is not: the weaker path was
reachable by any authenticated user and returned discounts the real redemption would refuse — a
customer shown a price and then charged more. Both the endpoint and the method are **deleted**,
along with their request/response records, so nothing can be written against them again. One
question, one implementation.

**2. "One coupon per booking" was never enforced.** Two things made it look like it was, and
neither was a guarantee:

- `CreateBookingRequest` carries a single `couponCode` — a property of today's DTO, gone the moment
  anyone adds a "change coupon" endpoint.
- `redeem` already had a duplicate check that reads as if it covers this. It does not. It is keyed
  on `(coupon_id, booking_id)`, which makes retrying the SAME coupon idempotent — right for a Feign
  timeout — and says nothing about a DIFFERENT coupon on the same booking. Two codes, two usage
  rows, the booking discounted twice.

Same shape as the payment-uniqueness problem in Session 50: a partial check plus a confident
comment, read together as a guarantee nobody had written. So V005 puts it in the database
(`uq_coupon_usage_one_per_booking`), with a backfill that keeps the EARLIEST usage per booking —
the price the customer was actually shown — and warns about what it removed. The service check
stays so the customer gets a sentence naming the applied code rather than a constraint violation.
Verified on real Postgres, seeded with a booking already holding two coupons.

**3. Support could not issue to a group, which a requirement explicitly asked for.**
`support_max_recipients` defaulted to **1**. An agent settling a salon-wide outage for forty
customers had to escalate every single one. Now 50. All four ceilings — ₹500 flat, 20%, 30 days,
50 recipients — remain `coupon_policy` rows, so changing them is an audited data change, not a
deploy.

**What I deliberately did NOT build.** `coupon.allows_wallet_stacking` is written and never read,
and I left it that way, documented. There is no wallet-spend path anywhere in the product — wallet
credit is a payment method, not a second coupon, and it belongs with the payment work. Inventing a
stacking rule for a feature that does not exist is how a column comes to claim a guarantee nothing
enforces, which is the exact mistake this session corrected twice.

#### Session 56 — the pre-launch batch: erasure, contact details, WhatsApp, console gaps

Six items off the pending register. Two of them I had reported wrongly, and correcting the record
was part of the work.

**1. Deletion requests were not deleting anything (V005).** bmp-admin closed a DPDP/GDPR erasure by
calling `deactivate`, and said so in its own recorded outcome. Two things made that worse than it
reads: every personal field stayed, and **deactivation is reversible by design** — bmp-auth
restores it on the next successful OTP login — so an erased account came back intact the moment its
owner signed in. A signed compliance record for something that had not happened.

Now `POST /users/{id}/anonymise`, SERVICE-only, called by the verified deletion flow. Name, email,
gender, age, photo and hair profile cleared. The **id survives** because `booking.customer_id`,
`invoice.customer_id`, `review.author_user_id` and `coupon_usage.user_id` are cross-service logical
refs with no FK — deleting the row turns a real appointment into a receipt with no customer.
Erasure covers personal data, not the commercial record tax law requires be kept.

The phone becomes `ANON-<id>`: unique, undialable, and it **frees the original number** so the same
person can sign up again. Without that, exercising a deletion right would permanently bar them.
`reactivate` now refuses an anonymised row — that path is the one that would have undone the whole
thing. A CHECK enforces that an anonymised row carries no name or email, because the erasing method
is exactly the kind of code that gains a field and forgets one.

**A bug the test caught.** `phone` is `VARCHAR(20)`; the tombstone is 41 characters. The first
version would have failed at runtime with a truncation error, on the compliance path. The column is
widened to 64 rather than the id truncated — a shortened id would turn "unique by construction"
into "unique probably", and UNIQUE on that column is what stops two erased accounts colliding.

**2. Company details are now one file with a build-time guard.** Registered name, address, support
phone, inboxes and team were scattered across two content files behind `TODO(pre-launch)` comments.
Scattered placeholders fail in a specific way: whichever page someone happens to open gets fixed and
the rest ship. `src/company.ts` holds all of it, every consumer renders **nothing** rather than a
marker when a value is unfilled, and `assertCompanyDetailsAreReal()` throws on a production build
listing exactly what is missing.

Three things deleted rather than defaulted: the invented `+91 80 0000 0000` (it rendered as a
tappable `tel:` link, so publishing it was the default, not the accident), map coordinates pointing
at a real Bengaluru location that is not our office, and three team members with bios none of them
approved. Comments do not fail builds; this does.

**3. WhatsApp reached exactly one call site.** SMS was wired broadly; WhatsApp only sent OTPs. The
day the Business account is approved and the flag flips, the channel would have carried login codes
and nothing a customer wants it for. Now wired alongside SMS at every booking moment, through a
`whatsappTemplate()` map from our template code to the Meta-registered name — returning null for
anything not yet approved, so approvals can trickle in without a message failing.

**4. Support console gaps.** Ticket rows showed a bare UUID; the requester's name is now resolved
from bmp-user for customer-raised tickets, non-fatally (a queue that fails to load because a name
lookup timed out is worse than a blank column). Per-customer booking count added as a one-integer
endpoint — the old TODO said it wasn't worth a call per row, which was right for the list and wrong
for the detail view, so it is on for one user and off for lists.

**5. Google sign-in was NOT a stub — I reported that wrongly.** It calls Google's tokeninfo, which
does full signature and expiry validation, then checks `aud` against our client id and
`email_verified`. The 501 fires only when no client id is configured, which is correct fail-closed
behaviour pending a Google Cloud OAuth client only the founders can create. One real gap fixed: the
`iss` check was missing. It cannot fail today, and it becomes load-bearing the moment somebody
swaps tokeninfo for local JWKS verification — which they should, since tokeninfo is rate-limited
and makes Google reachability a hard dependency of logging in.

**6. Referral payout stays deferred** at Darshan's earlier instruction.

Verified: 428 files parse, bmp-user migrations apply over a seeded real user, the anonymisation
CHECK refuses an incomplete erasure, `tsc --noEmit` clean.

#### Session 57 — the support organisation: tiers, a real queue, escalation and media

**The org model, compared against how the big Indian platforms run.** Swiggy, Zomato, Rapido and
Blinkit all run Zendesk/Freshdesk-shaped desks, and the shape is always four rungs: front-line
agent, team lead, ops, owner. BMP had **agent, ops and owner — and no lead**. That missing rung is
exactly why the ask reads as "support escalates to ops admin": with nothing in between, ops becomes
the first and only escalation, and a role meant for policy spends its day on individual complaints.
So `support_lead` was added, and it is the one change I made to what Darshan described.

`ops_admin (analysts)` maps to the existing **`read_only`** role at tier 0 — off the ladder
deliberately. An analyst who can be assigned a customer's complaint is not an analyst. Same for
`finance_admin`: refunds are a different axis, not a higher rung, which is why those companies route
money to a finance queue rather than up the support chain.

**Tier is an integer, not a role string.** Escalation is then one comparison. Deriving it from role
names puts the ladder in a switch statement inside whichever service escalates, and there is
eventually a second one that disagrees.

**"Not all ops admins can create accounts" is a capability flag, not a new role.**
`can_manage_staff`, granted individually by a super_admin, default false. Inventing
`ops_admin_senior` doubles the role list every time one person needs one extra power — and a flag
is how Zendesk and Freshdesk model it too.

**Escalation does not create a new ticket, and that IS the history requirement.** Messages belong to
the ticket; escalation raises its tier and records a `ticket_escalation` row. The receiving person
opens the same thread with every message, photo and prior handover in it. A fresh ticket per
escalation is precisely how a customer ends up repeating their problem to the third person they
speak to, and it splits the SLA clock so the new ticket looks fast while they have waited since
yesterday.

**Assignment is least-loaded, not strict round-robin.** A pure rotation keeps dealing to an agent
already holding four hard tickets. Ordering by `open_ticket_count` self-corrects — and **a new
joiner has a count of zero, so they are first in line automatically.** That is the whole mechanism
behind "new members are included in the queue": no roster, nothing to remember to update. A tier
with nobody in it is skipped on escalation, so a platform with no leads yet goes agent → ops and
starts using the lead tier the day somebody is hired, with no code change.

**Two things that were quietly broken.** `SupportDeskController.list` loaded every ticket and
filtered in Java — correct at ten, hopeless at ten thousand, and the queue is busiest exactly when
the platform is having its worst day; it is now a database query with an index. And
`support_message.attachment_url VARCHAR(500)` allowed **one** attachment, stored as a URL, which
quietly requires a public bucket — for threads containing faces, receipts and bank statements. Now
a table of storage keys with signed reads, matching how salon photos already work.

**Upload safety.** Type is DETECTED from magic bytes, never believed from the client's
`Content-Type`, and the allow-list is five formats. Verified against real headers: JPEG, PNG, PDF,
WEBP and iPhone HEIC accepted; a renamed EXE, an ELF binary, HTML, a ZIP and an SVG all refused.

Verified: 435 files parse, 9 admin migrations apply over staff seeded one-per-role (tier backfill
correct), escalation refuses downward/same-tier/reasonless moves, the 25 MB ceiling holds, 8
assignment cases and 11 sniffer cases pass, `tsc` clean.

#### Session 58 — the authority matrix: one mechanism for every gated action

Darshan: *"support can give discount and cash coupons up to a certain level; if not they pass to a
higher person. If a refund is required they pass to ops/finance. Even if ops admin can't, they pass
to admin. **I've given the example only for discount coupons — next it can be anything.**"*

That last clause is the whole design. A bespoke ladder for coupons, then another for refunds, then
another for waivers gives three subtly different escalation rules that drift apart — and the fourth
feature gets none, because by then nobody remembers there was a pattern.

**So: one matrix table, one approval table, one service, one console screen.** Adding a gated action
is a ROW plus an executor class. Not a controller change, not a queue change, not a UI change.

**The approver is a ROLE, not tier+1** — and Darshan's own example is why. Refunds go to FINANCE,
which sits at tier 0 and is off the support ladder entirely (V009). Money is a different axis from
seniority; a refund does not become approvable by being handed to a support lead. Coupons climb the
support ladder, refunds jump sideways to finance then up to ops, and both are just rows.

**`0` is a real answer, distinct from absent.** Support's refund ceiling is 0 — "may request, may
never perform". Without that distinction "cannot approve" and "not configured" read identically, and
the safe interpretation of the second is not the useful behaviour of the first. A missing row is
FORBIDDEN, never unlimited, so a new action added without matrix rows is impossible for everyone
below the owner rather than accidentally available to the whole desk.

**Approved and executed are separate states.** An approved coupon still has to be created and that
call can fail. A failure marks the request `failed` and **keeps the approval** — discarding it would
lose a real decision and make the retry look like a fresh request nobody signed off. The console
sorts those to the top, because somebody has probably already told a customer they are getting
something.

**Two mistakes caught while building.** I used `gen_random_uuid()` in the seed, which needs pgcrypto
and has no precedent anywhere in this repo — replaced with deterministic UUIDv5 literals so the seed
is idempotent and two environments can be diffed. And the coupon executor initially called a
`createCoupon` client method that does not exist; rewritten to reuse the EXISTING
`POST /internal/coupons` path so `CouponIssuePolicy` still applies — a second creation route would
have bypassed every ceiling that made the approval necessary.

Full mapping, including every seeded band and how to add an action, is in
`docs/AUTHORITY_AND_ESCALATION.md`.

Verified against real PostgreSQL: 16 authority decisions (support's ₹800 → lead, any refund →
finance, finance's ₹20,000 → ops, finance FORBIDDEN from coupons), the worked example end to end
(raised → lead escalates → ops approves → executed) with the trail keeping both decisions, and four
constraint guards. 446 files parse; console `tsc` clean apart from two pre-existing `vite.config.ts`
Node-types errors.

#### Still open

- **Wallet spending at checkout.** `allows_wallet_stacking` stays inert until it exists.
- **`mvn verify` has never run in the assistant's sandbox.** Everything rests on tree-sitter
  parses, arity checks, real-Postgres migration runs and reimplemented algorithms. **javac and
  IntelliJ remain the real check.**
- Razorpay's two HTTP calls (create-order, refund) — need an account.
- No customer-facing pay button: the backend confirms on webhook, but nothing opens a payment
  sheet yet.
- MinIO orphan-object sweep.

---

### Session 59 — the owner sets the ranges; nothing exceeds what was paid; the team, and their leave

Darshan, in one message: *"all ranges can be fixed by admin owner of bmp. next support or anyone
cant give coupon price more than what customer had booked. next all refund coupons etc all details
should be displayed for admin and finance admin they monitor. next queue monitor… can be a business
analyst or automatically… or manual also. next i need admin and ops admin to have feature to
maintain other memebers in team… mainly i need employee managemenet system… including leaves."*

Plus: *"check every flow even in tickets flow employee flow etc… build new standard features also
and approve for every recommemded one."*

#### 1. The authority bands became data

`GET/PUT /api/v1/admin/approvals/matrix`. Owner edits, ops and finance read, every change audited.
A band can never be set above the band it escalates to. The console renders the whole matrix as one
editable grid rather than a form per role — ₹2,000 for a lead only means something next to ₹500 for
an agent.

Ops deliberately cannot edit: a role that can raise its own ceiling doesn't have one.

#### 2. Nobody gives back more than the customer paid — including the owner

`GoodwillCapService`, checked at raise, at execute, **and** on the direct coupon path (goodwill
within somebody's own band never becomes an approval request at all). bmp-booking unreachable
**refuses** rather than failing open.

**V012 `goodwill_grant`** closed the hole that remained: the cap previously subtracted refunds only,
so a ₹600 booking accepted two ₹500 coupons — each passed, because a coupon never touches
`total_refunded`. The rule was enforced against one kind of goodwill and silently not the others.
Now every non-refund gesture is recorded at the moment it is granted, with a `CHECK` forbidding
refund rows (double-count) and a partial unique index on `approval_request_id` (retry double-count).

`record(...)` runs after the gesture succeeds and never throws — the customer already has the
coupon, and a false failure would have an agent issue it twice.

#### 3. One place where the owner and finance watch all of it

`/api/v1/admin/goodwill` + `/summary` + `/failed`, and `GoodwillPage` in the console. The ledger
merges approval requests **and** within-authority grants, de-duplicated by approval id — a ledger
built from approvals alone shows every escalated gesture and none of the routine ones, which is
backwards.

Given / awaiting / failed are three figures and are never summed. Failed rows — approved and never
delivered — sit at the top, because they are a promise outstanding rather than a cost, and ops can
read that endpoint too.

Per-agent totals: top ten, plain list, no chart. An agent who knows they are being ranked refuses
goodwill the business wanted given.

#### 4. Queue assignment: auto, manual, or an analyst

`queue_config` per tier (V011), all three modes through the one `autoAssign` so the fiddly parts
aren't reimplemented three times. `max_open_per_agent` pushes overflow into the visible pool instead
of onto somebody already full.

#### 5. Employee management, including leave

`TeamController` — roster with live load, employment record (job title, employee code, joined,
reports-to with cycle detection, shift note, last working day), availability pause, and leave:
request, approve, refuse, withdraw, and who's-off-today.

**Deliberately absent: salary, bank details, government identifiers.** Five roles can read the team
page, and putting pay one field away from a support console turns a console permission into a
payroll breach. If payroll is wanted it should be its own service with its own access model.

Nobody approves their own leave — including the owner. Approved leave takes the person out of the
ticket rotation.

Editing employment details cannot change role, tier or account status. A form labelled "job title"
must never be able to promote somebody. End-dating is separate from suspending the account: one is
an HR fact, the other a security action.

#### Bugs found by the flow trace Darshan asked for

- **`applyTodaysLeave()` was never called.** Written, documented, tested — and nothing scheduled it.
  Leave is requested in advance, so approving it did nothing on the day it started: an agent
  approved for next Friday would still have been handed tickets next Friday. Now
  `LeaveRotationJob` at 00:05 Asia/Kolkata plus one pass 30s after boot (a redeploy at 09:00 misses
  that night's run). *Pattern worth naming: a method whose javadoc describes a schedule is not
  scheduled by the javadoc.*
- **`support_lead` was locked out of the console.** The role has existed server-side since Session
  57; the console's `STAFF_ROLES` never gained it, so `roleAllowed()` matched neither door and a
  lead was bounced between them.
- **`/support/approvals` had no way in.** Session 58 built the page and the route; nothing linked
  to it.
- **`/matrix` returned rows that couldn't be told apart.** `LimitResponse` carried `approverRole`
  (who signs off *above*) but not `role` (whose band it *is*), so an editor could not know which
  ceiling it was about to change.
- **A comment claimed a distinction the code doesn't make.** The leave sweep's "back in" branch
  says it won't re-enable somebody who paused themselves; it can't tell the difference. Corrected to
  say what it actually relies on — that it runs at 00:05, before any manual pause exists — with an
  explicit warning not to move the schedule without adding a reason column first.

#### Verification

457 Java files parse, 0 errors. Console `tsc --noEmit` clean. V011 (12 cases) and V012 (9 cases)
run against real PostgreSQL, including the retry double-count and the exact ₹600-booking scenario
the old rule allowed.

#### Still open

- `mvn verify` **has still never run in the assistant's sandbox.** javac in IntelliJ remains the
  real check.
- The Session 58/59 console modules (approvals, team, goodwill) have **no mock layer on purpose** —
  an approval ladder that works against fixtures proves nothing. They show a `LiveOnlyNote` banner
  in mock mode; run with `VITE_USE_MOCKS=false`.
- No seed data for `staff_leave` or `goodwill_grant`.
- Razorpay create-order/refund still need real keys.

---

### Session 60 — clearing what was actually pending, and three things that were never reachable

Darshan: *"whats pending do it both front and back end"*. An audit of every TODO across the three
repos, then the ones that were real.

#### 1. Upholding a content report now removes the content

The console recorded moderation decisions and did nothing with them — the worst shape a moderation
tool can take. The audit log said the content was removed, the reporter was told it was handled, and
the abusive review was still on the salon's page. Invisible from inside the console, and the next
report looked like a duplicate.

- **V006 (bmp-review)** — `hidden_at` / `hidden_reason` / `hidden_by_staff_id`, with a CHECK that all
  three move together. Hidden, not deleted: moderation is reversible, `booking_id` is unique so a
  delete would let the same customer write a replacement, and the rating is history.
- **Every customer-facing read now filters it** — salon page, stylist page, average, histogram.
  Missing any one is how "we removed it" turns out to mean "from one of four places", and the star
  average is the one people trust most.
- **Photos are genuinely deleted** — nothing references a photo row. The asymmetry is deliberate and
  documented so it does not read as an inconsistency.
- **Profiles do nothing automatically.** The remedy is suspension, which ends somebody's ability to
  earn and has its own screen, note and role. Wiring it to a queue click would let an agent take a
  salon offline as a side effect of clearing their list. The moderator is told this, on the button.

#### 2. The DPDP data export exists

Erasure shipped in Session 56; access did not — which is backwards, since access is what people
actually ask for. "Still manual" meant an agent copying fields out of four screens and always
forgetting the support tickets.

`DataExportService` assembles profile + bookings + reviews (including hidden ones — the item the
person is least likely to know about) + tickets. A source that fails is **declared in the bundle**,
never silently omitted; the console repeats the warning before the agent can attach it to an email.

Gated on identity verification, because generating the bundle *is* the disclosure. Delivered by the
agent who verified them, not auto-emailed — the commonest reason for an export nobody requested is
that the account was taken over.

#### 3. The goodwill ceiling is visible before an agent promises anything

`GET /admin/coupons/goodwill-context` — what is left on the booking, **and what has already been
given, by whom**. The number alone ("up to ₹200" on a ₹600 booking) reads as a bug; the two earlier
₹200 coupons beside it make it obviously right, and tell the agent something that changes what they
say next.

#### 4. Smaller

- Half-day leave: the API and the V011 CHECK always accepted it; the form never sent it, so a dentist
  appointment cost a whole day.
- `RefundService` now uses the by-id booking lookup added in Session 59 instead of pulling a
  customer's entire booking list. *A guard justified by a limitation has to be revisited when the
  limitation lifts.*
- The salon-rejection TODO was **stale** — bmp-salon already emails via `setSalonStatus`.
  Implementing it would have double-emailed every rejected owner. Replaced with a pointer.
- `seed/dev-seed-team.sql`: six colleagues, leave in four states, queue config, goodwill history.

#### Bugs found while verifying

- **The entire Session 58/59 console surface was unreachable.** `approvals.ts`, `team.ts` and
  `goodwill.ts` wrote paths as `/admin/approvals`, and `client.ts` already sets baseURL to
  `/api/v1/admin` — so axios produced `/api/v1/admin/admin/...`. 21 paths, every one a 404. It hid
  because these modules deliberately have no mocks: an unexercised wrong path looks exactly like
  "no server running". *A feature that only works against mocks proves nothing; one that is never run
  against anything proves less.*
- **The team seed inserted nothing.** Every row was guarded `WHERE EXISTS (... role='support_agent')`
  and V003 seeds exactly one staff row — the superadmin. It reported success and produced the empty
  screens it was written to prevent. Caught by running it against real PostgreSQL rather than reading
  it. *A seed guarded on data it does not create is a seed that does nothing.*
- An invalid-hex UUID in that seed, plus a comment claiming a booking id matched `dev-seed.sql` when
  that file seeds no bookings at all.
- `DataExportService`'s header said "five sources" over four.

#### Verification

460 Java files parse, 0 errors. Console and BMP-FE `tsc --noEmit` both clean. V006 run against real
PostgreSQL with all three CHECK combinations refused; the twelve admin migrations plus the team seed
applied and re-applied idempotently, producing 4 leave rows, 2 goodwill rows and 4 queue rows.

#### Still open

- `mvn verify` **has still never run in the assistant's sandbox.** javac in IntelliJ remains the real
  check.
- Notification/consent history is absent from the export — bmp-notification records no per-recipient
  delivery yet. Named in the bundle's own notice rather than left implied.
- Push notifications; Razorpay create-order and refund (need an account); referral payout (deferred);
  customer-facing pay button; MinIO orphan sweep.

---

### Session 61 — the customer side: what a person can do about content, their data, and their own account

Darshan: *"Now in customer side any pendings?"* — then *"complete all the things… and also check all
booking related features done and also history cancel searching filters etc and also support raise
ticket coupons etc should be working."*

#### The audit first

Traced every customer path from screen to controller to gateway route. **Booking, support and
coupons are all correctly wired**, which is worth recording as a negative result:

| Flow | State |
|---|---|
| Book, cancel (+ fee preview), reschedule (+ eligibility), events, invoice, review | wired |
| Discovery search — `near`, `radiusKm`, `category`, `q` | wired |
| Support: raise a ticket, thread, reply, media | wired, correctly routed |
| Coupons: `/coupons/quote` at checkout | wired to bmp-rewards |
| Payment | still "coming soon" — blocked on Razorpay credentials, not code |

Two gaps found in booking, four elsewhere. All six built this session.

#### 1. Nothing could report content — the queue had no input at all

`content_report` has existed since Session 23. The console has a moderation screen. Session 60
wired upholding so it genuinely hides a review and deletes a photo. And the `ContentReport`
constructor was **never called from anywhere**: no endpoint created a row, no app had a Report
control.

Unreachable from both ends simultaneously, which is exactly why it survived so long — an empty
queue reads as "no bad content", not "no way to tell us about any".

- `POST /api/v1/content-reports` in bmp-admin (`ROLE_SERVICE`), `POST /api/v1/me/reports` in
  bmp-user, and a `ReportSheet` in the app.
- **One open report per person per item.** A second report by the same person returns the first and
  says so, so a double tap doesn't put two items in front of a moderator. Two *different* people
  reporting the same thing stay two rows — how many people reported something is the best triage
  signal there is.
- Reasons are a fixed list, not free text: a moderator sorting fifty items can't sort on prose, and
  `safety` can only jump the queue if it's a value the code compares.
- The confirmation promises a **look, not an outcome** — "we'll act on it if it breaks our rules",
  never "this has been removed".

Review reporting is wired end to end but has **no surface yet**: the salon page shows a review
*count*, not the reviews. Named in the code rather than left as a mystery.

#### 2. A customer could not ask for their own data

The console's data-request queue could only be filled by an agent typing on somebody's behalf.
Session 60 built the export that fulfils one. Under the DPDP Act the data principal needs a route —
and a queue only staff can fill is a process, not a route.

`POST /api/v1/me/data-requests` → a new `raiseBySubject` that audits the actor as **the customer**,
not as staff. Idempotent per person per type. Deletion asks twice and still only opens a request:
erasure is irreversible, and the commonest reason for a deletion request nobody made is that
somebody else has the phone.

#### 3. The wallet had never been reachable

Built in Session 47 — balance, transactions, referral code, all working — and **nothing linked to
it**. Now in the Profile tab rather than a fifth tab: a tab bar is for what you do often.

#### 4. The profile was read-only

`PUT /users/{id}` has existed since Session 12 and the app never called it. A name captured wrong at
signup meant opening a support ticket to fix one field. The phone number stays uneditable, with the
reason stated on screen — it's the login credential, so changing it is an authentication flow.

#### 5. Booking history had no search or filter

Upcoming/Past tabs only. Client-side, because the list is already fully loaded — with a note that if
the 50-row page size ever stops being enough, the filter has to move server-side **and** pagination
has to become real, since doing one without the other silently searches only the first page.

Controls appear only past four bookings, and a too-narrow search says "*n* bookings are hidden by
your filter" rather than showing an empty list that reads as "my bookings are gone".

#### Notable in the gateway

`/api/v1/me/**` routed to bmp-user. `/api/v1/content-reports/**` and `/api/v1/data-requests/**`
deliberately **not** routed anywhere — they're the `ROLE_SERVICE` receivers, called over the internal
network. Adding routes would put service-to-service endpoints on the public internet for no reason.
The fifth instance of the salon-shaped-path lesson: a route exists because something needs it, not
because the path exists.

#### Verification

465 Java files parse, 0 errors. Both frontends `tsc --noEmit` clean. Every new FE path checked
against its controller mapping and its gateway route by hand — the check that caught the doubled
`/admin/admin/…` prefix last session.

#### Still open

- `mvn verify` **has still never run in the assistant's sandbox.**
- Payment: Razorpay create-order and refund need real credentials.
- Push notifications; referral payout (deferred); notification history in the data export.
- Review reporting has no surface until the salon page lists reviews.

---


---

### Sessions 62–63 — staff logins that actually exist, and the two bugs that ate every OTP

#### The credentials problem was never a code problem

Three sessions were spent on `DevStaffSeeder` not producing accounts. The cause, every time, was
PowerShell scope: `$env:BMP_ADMIN_DEV_STAFF` set in one terminal, service started from another (or
from the IDE), seeder sees `enabled=false`, does nothing, says nothing. The seeder also refuses to
overwrite an existing account, so a second attempt with a new password looks exactly like a failure.

`seed/dev-staff-logins.sql` removes the variable from the equation: six accounts with **precomputed,
committed bcrypt hashes** — nothing to configure and nothing to scope wrongly. Password
`BmpLocalDev2026!Console`, shared TOTP secret `YKWFL6SICQZYDPCVN6UGLS4BYQOH5WQK`. It uses
`ON CONFLICT (id) DO UPDATE` — deliberately the opposite of `dev-seed-team.sql`, which leaves rows
alone. That file seeds a *roster*; this one seeds *logins*, and its entire job is to be the thing you
run when you cannot get in. A version that quietly skipped would reproduce the failure that made it
necessary. It also clears `failed_login_count` and `locked_until`.

It has **no localhost guard** — a SQL file cannot check its own target. The banner says so.

#### 2FA without a phone

`tools/totp.mjs` (CLI, `--watch`) and `tools/authenticator.html` (browser tab, secret pre-filled,
click-to-copy, greys out below 5s). Both HMAC-SHA1 / 6 digits / 30s to match `TotpService`, both
verified against all five RFC 6238 test vectors. These are **authenticators, not bypasses** — without
the secret they give you nothing. Console 2FA still cannot be skipped, on purpose: a bypass flag is
the thing that eventually ships enabled.

#### `tools/otp-doctor.sql` — and what it found

Four queries, one per hop: `otp_requests` → `common_schema.outbox` → `notification_log` → accounts
with no email. Read top to bottom; the first empty section is where it stopped. It never prints a
code — `otp_requests` stores a bcrypt hash, and a query that dumped live OTPs would be a credential
dump with a helpful banner.

Run against the real database it produced the diagnosis in one pass, and there were **two** bugs:

**1. Kafka `key.serializer`, in five services.** Spring Cloud Bus's `KafkaBinderEnvironmentPostProcessor`
silently defaults `spring.kafka`'s serializers to `ByteArraySerializer`. Outbox rows carried
`Can't convert key of class java.lang.String … specified in key.serializer`. This exact bug was
diagnosed and fixed **in Session 16 — and only in `bmp-auth`.** But `common_schema.outbox` is
**shared**: every service with `bmp.outbox.relay.enabled=true` polls it, so a relay in bmp-salon
picking up an `otp.requested` row died on the same serializer. Explicit `StringSerializer` blocks
added to bmp-salon, bmp-booking, bmp-admin, bmp-payment, bmp-rewards.

> **Fixing the service where a bug was *noticed* is not the same as fixing the service where it
> *lives*.** A shared table means a shared failure mode.

**2. `NotificationLogService.markSent` / `markFailed` had no `@Transactional`.**
`repo.findById(id).ifPresent(entity::markSent)` loads the row, mutates the object, returns. With no
transaction open the persistence context closes at the end of `findById`, the entity is **detached**,
and the mutation is discarded in silence. Every `notification_log` row sat at `status = 'queued'`
with `error_reason` NULL — throwing away the SMTP diagnosis `NotificationDispatcher` had carefully
built, which is why "OTP not arriving" had no error to read for weeks.

> **A setter on a JPA entity is not a write.**

Also: `requestOtp` now refuses to issue a code when no email address can be resolved, instead of
issuing an undeliverable one and reporting success. Email is still the only live channel.

#### Frontend

`LoginSheet.tsx` — the web sheet never rendered the Google button. `useGoogleSignIn`,
`loginWithGoogle`, `setFromGoogle` and server-side ID-token verification all already existed; only
`LoginScreen.tsx` rendered it. Sixth instance of *a feature is shipped when something navigates to
it.* Copy reworked so the sheet reads as one door for new and returning users ("Continue with your
phone" / "One is created for you when you confirm the code") — there is no separate signup, and the
old wording implied there was. `TextField` gained a `hint` prop.

Needs `EXPO_PUBLIC_GOOGLE_CLIENT_ID` in `BMP-FE/.env` and `http://localhost:19000` in the Google
Cloud authorised origins, or the button stays hidden.

#### Verification

All seven touched `application.yml` files parse; five now carry `StringSerializer` (bmp-notification
consumes, doesn't relay). 19 bmp-notification Java files parse, 0 errors. All six bcrypt hashes
verified against the password on a real PostgreSQL. `otp-doctor.sql` 4/4 queries verified against the
real schema — which is how `user_schema.otp_requests` (not `auth_schema`) and `failed_login_count`
(not `failed_login_attempts`) were caught. Both TOTP tools 5/5 RFC vectors. BMP-FE `tsc` clean.

#### Rotate before real data

The console password, the shared TOTP secret, and the Session-58 admin credentials are all in git.
Delete the dev accounts before anything real exists:
`DELETE FROM admin_schema.bmp_staff WHERE email LIKE 'dev.%@bemyprofessional.in';`



---

### Session 64 — the support desk: who is asking, who is working it, and who has it now

#### Two features that were built, correct, and unreachable

**`raised_by_type` was written on every ticket since V002 and rendered NOWHERE.**
`grep -rn raisedByType BMP-ADMIN/src` returned zero hits. An agent opening the queue saw a customer's
haircut question and a salon reporting it could not trade as two identical grey rows. That single
omission also disabled `priority`, which had existed and sorted correctly the whole time — you
cannot rank by who is blocked when who is blocked is invisible.

> **A column that is written and never read is a promise the UI never kept.**

**`TicketEscalationService.escalate()` had no controller, no route and no button.**
Written in Session 58, complete: tier ladder, ten-character reason floor, "you can only escalate what
you hold", auto-assignment to the receiving tier, a full `ticket_escalation` trail, and a database
CHECK enforcing upward-only moves. Nothing called it. Six sessions in which no ticket could be
escalated by anyone, and the only symptom was `escalation_count` permanently zero — which reads as
"we never need to escalate", not "escalation is unreachable".

> **Seventh instance: a feature is shipped when something calls it. A service class with no caller
> is a design document that compiles.**

#### V013 — requester identity and the assignment trail

`requester_name` and `salon_name` are **snapshots**, denormalised at raise time, exactly as
bmp-booking snapshots customer contact (V006). Two reasons: the queue lists fifty tickets, so a live
join is fifty cross-service calls per page load — and a failed name is indistinguishable in the UI
from a ticket that never had one. And a ticket is a record of a conversation that *happened*: if the
person renames themselves or exercises erasure, it must still say who it concerned at the time.

`priority_auto` records whether the value came from the policy or a person. Without it the only safe
design is "derive once at creation, never again" — which leaves a ticket later linked to a salon
carrying a priority computed when we thought it was a consumer.

`ticket_assignment` is append-only, with UPDATE and DELETE revoked at the database level and no
setters on the entity. Kept separate from `ticket_escalation` because they answer different
questions: escalation is *was this handled at the right level*, assignment is *who is working what
right now*. Merging them would make every workload query filter escalations out and every escalation
audit filter reassignments out.

#### `TicketPriorityPolicy` — and the line it will not cross

Salon-side outranks customer. Not snobbery — blast radius: a customer with a booking problem has one
spoiled appointment; a salon that cannot trade loses every appointment it would have taken today,
plus the customers who quietly went elsewhere.

**It never assigns `urgent`.** If the system can mint urgent, every category eventually becomes
urgent, agents learn to ignore the flag, and the top of the queue stops meaning anything. The policy
tops out at `high` and leaves `urgent` to a person. That is the important line in the file.

`raise()` previously hardcoded `"medium"` for every in-app ticket regardless of source.

#### V014 — desk is orthogonal to tier

`tier` is HOW SENIOR; `handling_desk` is WHICH FUNCTION. A refund dispute does not need a more senior
*support* person, it needs finance — who are deliberately tier 0 and off the escalation ladder
entirely. The ladder structurally cannot reach them, so escalating three times to reach the platform
owner and having them forward it by hand was the only available route.

So there are two verbs. **Escalate** = "this is harder than I can handle" (up a tier). **Transfer** =
"this isn't my job" (sideways). A tier-2 ticket at the finance desk is a normal state.

The tier-0 ban on holding tickets governs the AUTO-ASSIGNMENT engine. It does not extend to a human
deliberately handing finance a refund dispute. *A guard justified by one mechanism must not silently
govern a different one.*

Both moves demand ≥10 characters of reason, enforced server-side and mirrored in the button's
disabled state. The thread id never changes: the receiving person opens the same conversation, and
the customer sees one visible line saying it changed hands and they need not re-explain. The reason
itself stays internal — it can carry candid assessments.

#### `TicketHandlingState` — and what it deliberately does not claim

Eight states derived from columns that already exist, never stored. A stored `chat_state` would be a
second source of truth that drifts the first time somebody updates a ticket through a path that
forgets to maintain it — and that path will be added in six months by somebody who has not read this.

The chat banner previously appeared **only** on escalation. Every other state, including the whole
first stretch after someone hits send, showed nothing: the person saw their own message and no sign
anyone had read it. `status` said "open", which is true and useless — it says the same thing ninety
seconds in and three days in.

> Silence in a support chat is not neutral. People read it as being ignored, and the reliable
> consequence is a second ticket about the same problem, which lengthens the queue and makes their
> own wait worse.

**No "agent is typing", no "connecting…", no read receipts.** There is no live socket, so all three
would be comforting animations with nothing behind them. Every state corresponds to a stored fact.
A status that turns out to be untrue costs more trust than no status at all.

#### Oversight, and the nav

`OversightPage` — every open ticket with its holder, per-agent workload (open / high / breached /
*taking work*), assign–reassign–release, and the movement trail. Separate from "All tickets" because
they answer opposite questions: that screen is for WORKING a queue and hides what you cannot act on;
this is for DECIDING HOW WORK IS SPREAD and must show everything, including other people's. Folding
them together turns an agent's working view into a floor-wide league table.

The tell that it was needed: a ticket assigned to somebody who had left simply stopped moving, with
nothing anywhere reporting it. **A queue with no supervisor view does not fail loudly; it goes quiet.**

`DeskNav` — the owner dashboard had 21 tabs in one non-scrolling `View`. Everything past the viewport
edge was laid out and unreachable: no scrollbar, no wrap, no affordance. Grouped into five sections
that always fit. *A layout that works at six items is not a layout that works at twenty; the failure
mode of too many children in a fixed row is silent truncation, not a warning.*

First version let you open a group without navigating, so the header said People and the body said
Desk. That imported a desktop-menu idea into something that is not a menu. **Tapping a group
navigates.**

#### Closures were reachable and undiscoverable

V019 has done festival and one-off closing since Session 48, and `AvailabilityService` has subtracted
every window from every slot search. Two things were missing, both in the UI:

- The owner looking for "close this Thursday" went to **Opening hours**, which sets the WEEKLY
  pattern — switching Friday off closes every Friday from now on. That screen now signposts Closures.
- The **customer** was never told. A salon shut for Diwali looked identical to a busy one: page
  rendered, date strip offered the day, tapping returned an empty list. "No slots" and "closed" are
  different facts and people act differently on each — the first means try another time, the second
  means try another day or another salon.

#### The chat thread, rebuilt

Sides (staff right, requester left), day separators (`Today` / `Yesterday` / a date), and consecutive
messages from one speaker no longer repeat the timestamp. Internal notes keep the dashed amber border
*and* a distinct full-width shape — that confusion is the expensive one, an agent writing candidly
believing a note is private or withholding a reply believing it was already sent.

#### Bugs caught in this session's own work

- Console endpoints written as `/oversight/...` when `SupportDeskController` is mapped at
  `/api/v1/admin/support` — every call would have 404'd. Invisible against mocks, because the mock
  branch returns before the URL is built. Same failure as Session 58 and the Offers tab before it.
- `fromDesk` read *after* the setter, so the audit would have logged "finance → finance".
- Four mock tickets missing the new fields — caught by `tsc`, which is the contract guard working.

#### Verification

469 Java files parse, 0 errors. All 14 bmp-admin migrations apply in order on a real PostgreSQL; the
`handling_desk` CHECK was confirmed to reject a bad value rather than merely being declared. Both
frontends `tsc --noEmit` clean. Every new console path checked against its controller's
`@RequestMapping`, not its method mapping.

**`mvn verify` still has never run in the assistant's sandbox** — Maven Central is unreachable, so
compile-level confirmation remains the team's to run.



#### Session 64, continued — push, the export gap, and the referral that never paid

**Notification history in the DPDP export.** The bundle's notice used to end *"It does not yet
include a log of messages we sent you"* — honest, and now obsolete. Five sources instead of four.

Two limits kept deliberately. The message **body** is not exported: `notification_log.payload` holds
template variables that routinely name a THIRD PARTY (a booking notification carries the salon and
stylist; a closure notice can carry a manager's phone number). Same reasoning that already excludes
support message threads — disclosing somebody else's data as part of a subject's export is a
different decision from disclosing the subject's own. And **failed** sends are included, because
"we tried and it bounced" is a materially different fact from "we never sent it".

A purpose-built `/export` endpoint rather than reusing `/recipient/{id}`: that one returns a Spring
Data `Page`, which does not deserialise into a list without `PageJacksonModule` in every consumer —
and the failure mode when somebody later forgets it is an empty section in a legal disclosure.

**Push notifications, via Expo.** `push_token` (V004) is per-DEVICE, not per-person: the same account
on a phone and a tablet is two tokens and should get both. Owned by bmp-notification because a push
token is a delivery address, exactly as an email address is for SMTP, and only bmp-notification ever
sends.

The unique index is on `token` ALONE, not `(user_id, token)` — verified against a real database. A
phone handed from one person to another keeps its token, so re-registering must MOVE it; a
per-user constraint would let the new owner receive the previous owner's booking notifications.

Expo answers **HTTP 200 even for a token it rejects** — the per-message outcome is inside the body.
Treating 200 as success would mark every send delivered, including to tokens Expo has just declared
dead, and the log would show a healthy channel reaching nobody. `DeviceNotRegistered` is the normal
end of a token's life, logged at INFO: alerting on it would train everybody to ignore this channel's
alerts.

> **Push is an ADDITION to a durable channel, never a replacement.** Treating a push as "they have
> been told" is how a salon cancellation ends with somebody standing outside a locked door.

The registration endpoint was first written INTO bmp-notification and moved out again. That service's
config says explicitly it is written to by other services and never by an app, and its public-paths
list exists because an earlier version left the whole message history open. The app now calls
`/api/v1/me/push-token` in bmp-user, which fills the user id from the verified JWT — a caller-supplied
one would be an interception endpoint with a friendly name.

**The referral that never paid.** Everything existed except the wire:

- `referral.completed_at` — V002, commented *"set on referee's FIRST COMPLETED VISIT"*
- `ReferralService.completeOnFirstVisit(...)` — fraud and expiry guards, fully written
- `BookingCompleted` — published by bmp-booking on every completed appointment

Nothing connected them. bmp-rewards had **no Kafka listener at all**, so the method had no caller and
referrals accumulated attribution for months while paying nothing. Its own javadoc said so: *"Do not
advertise a referral programme until this is wired."*

> **Eighth instance: a service method with no caller is a design document that compiles.**

The `TODO(payout)` said *"Needs bmp-payment"* and was wrong — that assumed a CASH payout. Wallet
credit is entirely internal to bmp-rewards: no gateway, no KYC on the referrer, no TDS, no
reconciliation ledger. It is self-funding (credit costs margin, not cash) and can only be spent with
us, which is the point of paying for a referral.

Released on the referee's first **completed** booking, never signup: signups are free to manufacture,
and paying for them pays for accounts rather than customers. Not on payment either — a paid booking
can still be refunded, and clawing back a spent wallet credit is usually impossible.

Idempotency lives on the ENTITY (`Referral.markCompleted()` returns false once settled), not in the
listener: `booking.completed` is at-least-once, and a second consumer added later would have to
remember to re-implement a check placed in the listener, and would not.

Reward amounts are read from the FROZEN columns, not from today's config. Somebody who referred a
friend under a ₹150 offer is owed ₹150 even if the rate is ₹50 by the time the friend books.

**And the same Session 63 bug, mirrored.** bmp-rewards had explicit producer serializers and no
CONSUMER deserializers — it had never consumed anything. Adding a listener without them would have
failed on every message, and failed *silently from outside*: referrals would simply never pay, which
is indistinguishable from the state this work set out to fix. `auto-offset-reset: earliest` too, or a
brand-new consumer group skips every booking completed before it first started.

#### Still open after this

- **`mvn verify` has never run in the assistant's sandbox.** Maven Central is unreachable; everything
  above rests on parse checks, real-database migration runs and hand-checked arity, not a compiler.
- `npx expo install expo-notifications expo-device`, then delete `src/types/optional-native-modules.d.ts`.
  Until then push degrades to nothing, by design. Push does not work in Expo Go on Android SDK 53+.
- `http://localhost:19000` in the Google Cloud authorised origins.
- Rotate the committed dev console password and TOTP secret before real customer data exists.
- Razorpay create-order and refund need real credentials.
- Review reporting still has no surface until the salon page lists reviews.

### Session 65 (part 2) — account administration, scoped by whose account it is

**What was asked.** *"Number changes, email changes, account block, account remove of customers can
be done by support ... customer and support accounts can be done by ops admin ... all kind of
accounts can be done by main admin."*

**The thing that nearly went wrong.** The obvious reading — one set of endpoints over "accounts" —
is wrong, because BMP has **two account tables**. `user_schema.users` holds customers and
salon-side people who sign in with a phone and an emailed code. `admin_schema.bmp_staff` holds the
console's own people, who sign in with a password and TOTP. A support agent's console account is
not a row in `users` at all, so "ops admin can manage support accounts" could never have been
satisfied by the user endpoints, however well they were written. Two scope classes, each naming its
table: `AccountScope` and `StaffAccountScope`.

**Load first, authorise second.** `@PreAuthorize` can only prove the caller is staff. The real
question is *may this caller act on THIS account*, and that depends on the **target's** role, which
is unknown until the row is read. Every one of these endpoints therefore loads the target before
deciding — the reverse of the usual order, deliberately.

**Two drifts found while doing it, both of the same shape — a second copy of a rule with nothing
checking it against the first:**

- `StaffPermission.permissionsFor(super_admin)` hand-listed its permissions while `has()` used a
  wildcard. The three new `account:*` permissions were granted by `has()` and missing from the
  list, so the owner would have had endpoints that worked and buttons that were hidden. It now
  derives the set by reading the constants off the class. The javadoc above `ROLE_PERMISSIONS` had
  warned against exactly this; the method simply had not followed it.
- The self-edit check in `AccountScope` compares a `bmp_staff` id to a `users` id. Those come from
  different tables and are never equal, so it does not fire. It is kept (it costs nothing and
  becomes real if the identities are ever linked) but the comment now **says so** rather than
  claiming a protection that isn't there. The self-edit risk that exists today is on the staff
  side, where `StaffAccountScope` compares two ids of the same kind and the check means something.

**Ops admins can now reach Staff accounts.** The screen was owner-only, which meant one person had
to be free before a departing agent's access could be revoked — not an offboarding procedure. Ops
now creates, suspends and re-credentials the desk (agents, leads, finance, read-only) and can do
none of those to another ops admin or the owner, or to themselves. The role dropdown only offers
roles the caller may actually create, and rows they cannot act on show *"Owner only"* or *"This is
you"* instead of buttons that would 403.

**Remove means anonymise.** The row survives with its identifying fields cleared, because a
person's past bookings are also the salon's record of work it performed and was paid for. The hard
delete in `tools/delete-test-accounts.sql` is for the two test numbers only and says so in its
header.

**Verified:** 486 Java files parse clean; `tsc --noEmit` clean in BMP-ADMIN; every identifier in the
new console code checked against the real field and method names (the first draft of these endpoints
used four that did not exist). **Not verified by a compiler — `mvn verify` still has never run here.**

### Session 65 (part 3) — the Block button was doing nothing

**Found while checking what the app should show a blocked person.** The feature shipped an hour
earlier called `deactivate()`, and bmp-auth reactivates a deactivated account on its owner's next
OTP login — a line written for people who pause their own account, which cannot tell that case from
a staff block. So the block lifted itself at the next sign-in. Separately, neither `refresh` nor
`me` read the flag, so anyone already signed in kept working for days.

Everything worked: the button, the audit entry, the confirmation. **The only thing that did not
happen was the block.** This is the same shape as the session's other finds — a feature is shipped
when something CALLS it, and a call to the wrong thing is indistinguishable from a call to the
right one until somebody traces it.

**The fix is a separate `blocked_at`**, because `deactivated_at` and a block say opposite things
about the person's wishes and one column cannot hold both. Refused at login (before the
reactivation line — the ordering is the fix), at refresh (which also revokes the token), and at
`/me` with a 403 rather than a 401 so the app can distinguish it from an expired session.

**The 15-minute gap is not closed and the comments say so.** An access token already issued stays
valid until it expires. Revoking refresh tokens ends the session at renewal rather than instantly.
Fixing it properly needs revocation checks on the resource services.

**BMP-FE gained a fourth session state.** `blocked` is not a flavour of `guest`: a guest can sign
in, and sending a blocked person to the login screen produces a loop — number, code, refused — that
reads as a broken app and hides that the block worked.

**Self-service contact change, and an unverified email edit removed.** The profile form was
changing the email address outright with nothing confirming it. Login codes arrive by email, so
anybody holding a session could redirect them and own the account. Both phone and email now go
through a code. The honest limit: for a PHONE change the code goes to the existing email, because
SMS cannot deliver until DLT registration completes — it proves the requester, not the number, and
the screen says exactly that rather than implying a check that did not happen.

**Verified:** 491 Java files parse clean; both migrations run against real PostgreSQL, including
the property the block depends on (the reactivation UPDATE clears `deactivated_at` and leaves
`blocked_at` standing) and the CHECK that rejects a targetless contact-change row; `tsc --noEmit`
clean in BMP-FE and BMP-ADMIN. **Still no compiler — `mvn verify` has never run here.**

### Session 66 — a stylist's specialisations, and a validation hole in the table that held them

**`stylist_service` had existed since V002 with endpoints since Session 24 and no caller in any
app.** The table was empty in every environment, so nobody could say which services a stylist
performs — and the per-stylist duration, which the availability algorithm reads, was unreachable.

`addService` was three lines: construct from the request, save, return. **It validated nothing.**
The controller authorises the caller against `salonId` in the PATH; `serviceId` then arrived in
the BODY and was written straight to the row, so an owner could attach another salon's service to
their stylist by pasting an id. Same shape as the closure-cancel and join-request holes — *an
authorised path says who is calling, not what their ids refer to.* It now checks four things: the
stylist is active here, the service belongs to this salon, it is not archived, and it is not
already assigned. Plus a 5-minute-to-8-hour duration range, because that number sizes real
appointments.

`PUT` and `DELETE` were also simply absent, which is *why* the duplicate mattered — an owner who
got it wrong could only add it again with different numbers. Added both, and a `@PreAuthorize` on
the GET, which had none and was falling through to the filter-chain default.

Also this session: the stylist can now READ their own working hours (`GET
/api/v1/stylist-profile/hours`) and the days the salon has marked them off. Every method on
`StylistAvailabilityController` was owner/manager-only — correct for writes, but it gated the read
too, so *the person expected at the chair was the only party who could not see the shift they were
expected for.*

### Session 67 — Darshan cut the per-stylist timings, and he was right

> "Why we required services plus timings ... timing is standard for service and applicable all
> stylish ... stylist can choose service which is salon itself"

How long a haircut takes is a property of the haircut. Modelling it per stylist does not capture a
real distinction so much as manufacture one, and then asks somebody to maintain stylists × services
numbers forever — 3,000 of them for a 100-stylist salon. Nobody does, they go stale, and the
booking algorithm starts sizing appointments off figures no one believes. Same argument removed the
price override: one price per service.

What remains is membership, which is a set — so three endpoints (add / edit / remove) collapsed to
one replace-set `PUT`. That deletes a class of bug rather than handling it: no duplicate check, no
ordering question, no partial-failure state.

`actual_duration_minutes` survives as a NOT NULL column, written from the service's own duration
and read by nothing. A later migration can drop it.

### Session 68 — "fully booked" was a lie, five different ways

Darshan, on a brand-new salon with zero bookings, was told his stylist was fully booked — on a page
that printed "Hours not set yet" three inches away.

`freeSlots` returned an empty list for **five unrelated reasons** and every screen rendered all
five identically: suspended, date out of range, salon has no opening hours, stylist has no rota,
genuinely full. Only the last is "fully booked". **This is not an edge case — a salon that just
signed up has neither hours nor a rota, so every new salon on BMP is told its staff are fully
booked on day one.**

Same lesson as Session 66's fail-closed availability, in a new place: *a computation that produced
nothing is not a computation that means nothing is available.* `freeSlotsExplained` now returns
slots plus a `NoSlotReason`, and `salonLevelReason` holds the salon-wide checks in one place so
`salonDayAvailability` — a deliberate second copy of the arithmetic — cannot drift on the reasons
too. Staff get the cause and the screen to fix it; customers get a neutral sentence, because a
salon's unconfigured rota and a stylist's suspension are not a customer's business. The staff test
is derived from the principal, never a request parameter.

**Two bugs were hiding behind it**, both in the customer booking panel. The slot renderer walked
`{label, slots:[{time, available}]}` — a shape the endpoint stopped returning in Session 38 — so
`group.slots.map` throws on real data; it never surfaced because reaching that branch required
slots to exist, and there never were any. Fixing the availability bug alone would have turned
"fully booked" into a white screen. The `noSlots` test had the same fault: `.every()` on an empty
array returns true without calling the predicate, so it was accidentally correct in exactly the one
case it was written to detect.

**Also fixed: a locale bug that broke every booking path.** Four Feign `LocalDate` params carried
no `@DateTimeFormat`, so Spring encoded them with the JVM's default locale — `06/09/2026` on an
Indian machine — while the receiving controller parses ISO only. It compiles, and it passes on any
machine whose locale happens to be ISO, so it reads as "works on my machine". `FeignDateEncodingTest`
now asserts the annotation on every date param by reflection.

**Docs (Session 68).** All three `RUN_LOCALLY.md` files gained a fast path and a complete
install-this table. Two real drifts fixed while doing it: the backend guide claimed **3** infra
containers when `docker-compose.yml` has **4**, and **MinIO was never mentioned anywhere** — so a
teammate following the guide had image upload fail with a connection error and nothing explaining
it. The console guide now documents the `LOCKED-NO-PASSWORD-SET` sentinel and the seed file, which
had cost two sessions.

## How to Add to This File

When you finish a session:
1. Add a new `### Session X` heading to [Full Session Log](#full-session-log--every-chat-turn-summarised)
2. Update the status tables at the top with ✅ or 🔜
3. List the commits pushed (or link them)
4. Flag any team ratification needed (like the Session 5 microservices decision)
5. DO NOT change [Locked Decisions](#locked-product-decisions) — raise a PR for debate if needed
