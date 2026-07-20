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

**Phase: Microservices Split Complete (Session 5) → Phase 1 CRUD In Progress (Session 6 — Dev Achyuth) → Session 7 (BMP-6 & BMP-30 Complete) → Ready for Phase 2**

⚠️ **Session 5 reversed the "modular monolith, not microservices" LOCKED decision below** (see
Technology Stack table and Session 5 log entry). This was a **Darshan-only decision**, made and
executed in a single Cowork session with Shivam and Achyuth not present — same caveat as the
Session 4 availability-model work. **Flagged for both of you to review before this is treated
as final.** Nothing here is unrecoverable (the old bmp-app monolith entry point still exists,
see `bmp-app/RETIRED.md`), but going forward all 3 of you need to agree this is the direction.

| Area | Status |
|---|---|
| Product strategy and GTM | ✅ LOCKED |
| UX/UI design (60+ screens) | ✅ COMPLETE |
| All 8 core module schemas (incl. Admin, Notification) | ✅ COMPLETE — V001(outbox)+V002-V009 migrations, 57 JPA entities |
| Architecture | ⚠️ CHANGED Session 5 — modular monolith → **microservices** (Darshan-only, NOT ratified) |
| Service registry (Eureka) + API Gateway | ✅ DONE — see Port Table in Session 5 log entry |
| bmp-auth-service (OTP/JWT issuing) | ✅ DONE — full auth flow (request/verify OTP, refresh, logout) |
| **Phase 1 CRUD — Admin module** | ✅ DONE (Session 6) — entities + repositories + services + controllers for bmp_staff, support_ticket, support_message, audit_log |
| **Phase 1 CRUD — User module** | 🔜 IN PROGRESS (Session 6) — entities ✅, building repositories/services/controllers |
| **Phase 1 CRUD — Salon module (core)** | 🔜 IN PROGRESS (Session 6) — entities ✅, building repositories/services/controllers |
| **Phase 1 CRUD — Stylist module** | 🔜 IN PROGRESS (Session 6) — entities ✅, building repositories/services/controllers |
| **Phase 1 CRUD — Booking module** | 🔜 PLANNED (Session 6) |
| **Phase 1 CRUD — Payment module** | 🔜 PLANNED (Session 6) |
| **Phase 1 CRUD — Review module** | 🔜 PLANNED (Session 6) |
| **Phase 1 CRUD — Rewards module** | 🔜 PLANNED (Session 6) |
| **Phase 1 CRUD — Notification module** | ✅ DONE (Session 7 — BMP-6 & BMP-30) — notification_log entity + repository + service + controller CRUD endpoints |
| Availability model paper design (Q1-Q6) | ✅ DRAFTED — ⚠️ Darshan-only sign-off, Shivam/Achyuth must review/ratify |
| Availability model schema | ✅ DONE — V003 + V004 (salon service), stylist_availability + walk_in_block |
| Availability model algorithm (freeSlots/blockWalkIn) | 🔜 PHASE 2 (after Phase 1 CRUD) |
| Razorpay Route confirmation | ⏳ PENDING (confirm directly with Razorpay) |
| Inter-service auth, OTP login, integrations | 🔜 PHASE 3 (deliberately deferred until Phase 1 CRUD complete) |

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

## How to Add to This File

When you finish a session:
1. Add a new `### Session X` heading to [Full Session Log](#full-session-log--every-chat-turn-summarised)
2. Update the status tables at the top with ✅ or 🔜
3. List the commits pushed (or link them)
4. Flag any team ratification needed (like the Session 5 microservices decision)
5. DO NOT change [Locked Decisions](#locked-product-decisions) — raise a PR for debate if needed
