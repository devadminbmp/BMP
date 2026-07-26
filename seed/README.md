# Seed data — surviving `docker compose down -v`

The local database lives in a Docker volume. Any time migrations change (or something gets
into a bad state) the fix is `docker compose down -v`, which **deletes every row**. So no
demo data should ever exist only in Postgres.

**The durable copies live in git:**

| What | Where | Used by |
|---|---|---|
| Backend demo rows (salons, services, stylists, staff, users) | `seed/dev-seed.sql` | the real API |
| Frontend demo data (same ids/names/prices) | `BMP-FE/src/api/mocks/seed.ts` | the app in mock mode |
| Demo photos | `BMP-FE/assets/salons/` | both (bundled, offline-proof) |

The two datasets are deliberately **mirrored** — same ids, names and prices — so the app
looks identical whether it's reading mocks or the live backend.

## Re-seed after a reset

```powershell
# 1. containers up
docker compose up -d

# 2. start the services ONCE so Flyway creates the schemas/tables, then:
docker exec -i bmp-postgres-1 psql -U bmp -d bmp < seed/dev-seed.sql
```

Idempotent (`ON CONFLICT DO NOTHING`) — safe to run repeatedly.

Verify:
```powershell
docker exec bmp-postgres-1 psql -U bmp -d bmp -c "SELECT count(*) FROM salon_schema.salon;"   # 8
```

## Demo accounts

Log in with any of these phone numbers (OTP arrives by email to the listed address — or
read it from the bmp-notification console):

| Role | Phone | Email |
|---|---|---|
| Customer | +919876500001 | priya.customer@example.com |
| Customer | +919876500002 | arjun.customer@example.com |
| Salon owner (Lumière) | +919876500003 | owner.lumiere@example.com |
| Manager (Lumière) | +919876500004 | manager.lumiere@example.com |
| Stylist (Lumière) | +919876500005 | ravi.stylist@example.com |
| Salon owner (Aura) | +919876500006 | owner.aura@example.com |

> These emails are examples and can't receive mail. To actually receive the OTP, either
> read it from the bmp-notification console, or seed a user with a mailbox you control.

## Adding demo data

Add it to **both** files (`seed/dev-seed.sql` and `BMP-FE/src/api/mocks/seed.ts`), keeping
ids consistent. Money is always **integer paise**.
