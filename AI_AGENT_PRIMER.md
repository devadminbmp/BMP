# BMP — AI Agent Quick Primer

> Paste this at the start of ANY AI session (Claude, GPT, Anti-Gravity, Gemini, etc.)
> Then say: "Read CONTEXT.md for full details. Today I want to: [YOUR TASK]"

---

## What is this project

BMP (Be My Professional) — premium salon booking platform, Bengaluru, India.
3 co-founders. Java 21 + Spring Boot 3.4.1 + Spring Modulith. PostgreSQL + MongoDB.
Pre-PMF. Schema design complete. Starting backend development.

## The 9 rules that can never be broken

```
1. Money = BIGINT paise (integer). ₹1 = 100 paise. NEVER floats or decimals.
2. All PKs = UUIDv7. Never sequential integers.
3. Razorpay webhook = ONLY payment truth. Never trust client SDK callback.
4. BMP never holds customer funds. Razorpay Route splits at capture.
5. Prices, duration, policy FROZEN at booking creation. Never updated.
6. Stylist identity portable. stylist rows never deleted, only alumni.
7. booking_events and audit_log APPEND-ONLY. DELETE/UPDATE revoked at DB level.
8. bmp_staff is COMPLETELY SEPARATE from users. Separate auth, separate table.
9. ArchUnit bans BigDecimal across the entire codebase.
```

## Architecture (locked, do not suggest changes)

Multi-module Maven monolith. One Spring Boot deployable. One PostgreSQL database.
Modules: bmp-common, bmp-user, bmp-salon, bmp-booking, bmp-payment,
         bmp-review, bmp-rewards, bmp-admin, bmp-notification, bmp-app

Each module owns one PostgreSQL schema. Module boundaries enforced by ArchUnit.
Kafka replaced by Postgres Outbox + relay worker.

## What is complete

- All 6 core module schemas (User 5 tables, Salon 12, Booking 8, Payment 9, Review 11, Rewards 10)
- Maven skeleton code with Money.java, UuidV7.java, BookingStatus.java, OutboxPublisher.java
- 60+ UX/UI screens across 4 platforms
- Master schema document (BMP_Core_Module_Schema.docx — 3741 paragraphs)
- Schema diagrams (docs/schema-diagrams/*.png)

## What is next

1. Admin module schema (bmp_staff, support_ticket, support_message, audit_log)
2. Notification module schema
3. Availability model implementation (HIGHEST PRIORITY)
4. LLD / API contracts
5. Backend development starting with Salon module

## How to continue

1. Read CONTEXT.md for the full picture
2. Read the relevant module section in CONTEXT.md for whatever you are working on
3. When you finish a session, add your decisions to CONTEXT.md under Session Log
4. Never change a locked decision. If you think one should change, flag it and let the team discuss.

## Do NOT suggest

- Microservices (we use modular monolith)
- Kafka (replaced by outbox)
- BigDecimal for money (integer paise only)
- Separate databases per module (one Postgres, multiple schemas)
- BMP holding customer funds (Razorpay Route splits at capture)
- Building before Phase 0 validation (kill criteria must be set first)
