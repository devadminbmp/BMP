# BMP Platform — Modular Monolith (multi-module)

One deployable. Eight business modules. One PostgreSQL (schema per module, real FKs).
Postgres outbox instead of Kafka. Boundaries enforced by the build, not by discipline.

## The four rules
1. A module's `internal` package is invisible to every other module. Talk through
   `api` interfaces (sync) or outbox events (async). `mvn verify` fails otherwise.
2. Every module owns exactly one DB schema. Nobody queries another module's tables.
3. Cross-module events go through `OutboxPublisher` inside the same transaction
   as the business change. Consumers are idempotent.
4. Money = `Money` (integer paise). Keys = UUIDv7. Booking status changes only via
   the `BookingStatus` state machine.

## Run
    docker compose up -d
    mvn verify                      # includes architecture tests
    mvn spring-boot:run -pl bmp-app

## Module map
    bmp-common        shared kernel: Money, UuidV7, DomainEvent, outbox
    bmp-user          auth, OTP, profiles, roles
    bmp-salon         salons, stylists, policy, ⚠️ availability model (design first!)
    bmp-booking       bookings, slot locks, state machine     → depends on salon+user api
    bmp-payment       Razorpay Route, webhooks, payouts        → depends on booking api
    bmp-review        verified reviews
    bmp-rewards       coupons, wallet, referrals
    bmp-admin         bmp_staff, tickets, append-only audit log
    bmp-notification  outbox processor, WhatsApp/SMS/push
    bmp-app           the one Spring Boot jar + Flyway + architecture tests

## Extraction path (later, on evidence)
Pick a module → its api becomes a REST client → its schema moves to its own DB →
its outbox events move to a real queue. Weeks, not a rewrite.

## Before writing the availability migration (V004)
The availability model must be designed on paper against 3 real pilot salons'
actual weeks and approved by all founders. See AvailabilityApi javadoc for the
questions the design must answer. This is the highest-priority technical task.
