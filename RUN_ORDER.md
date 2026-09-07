# How to run BMP locally — the order that works

Last updated Session 48.

Everything here is run from `C:\BMP -PRJ`. Three repos: `BMP` (backend), `BMP-FE` (customer + salon
app), `BMP-ADMIN` (staff console).

---

## The 30-second version

```powershell
# 1. infrastructure
cd "C:\BMP -PRJ\BMP" ; docker compose up -d

# 2. backend — recompiles automatically
.\run-service.ps1 eureka-server          # wait for it to be up before the rest
.\run-service.ps1 bmp-auth
.\run-service.ps1 bmp-user
.\run-service.ps1 bmp-salon
.\run-service.ps1 bmp-booking
.\run-service.ps1 bmp-review             # Session 60-61: moderation + the DPDP export read it
.\run-service.ps1 bmp-notification
.\run-service.ps1 bmp-admin
.\run-service.ps1 api-gateway            # last

# 3. seed (once, after the services above have run their migrations)
#    docker cp, NOT `psql -f` — see the note below.
docker cp seed\dev-seed.sql           bmp-postgres-1:/tmp/s1.sql
docker cp seed\dev-seed-discovery.sql bmp-postgres-1:/tmp/s2.sql
docker cp seed\dev-seed-team.sql      bmp-postgres-1:/tmp/s3.sql
docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/s1.sql
docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/s2.sql
docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/s3.sql

# NOTE (Session 61): the seed lines above used to read `psql -h localhost -U bmp -f seed\...`.
#   Two things were wrong with that. It needs psql installed on the host, which nothing else in
#   this setup does — and the seed contains `Lumière`, which a host-side pipe can re-encode.
#   `docker cp` + `docker exec` avoids both and matches what RUN_LOCALLY.md §5b says.
#   `dev-seed-team.sql` is new: without it the console's Team, Leave, Queues and Goodwill screens
#   are empty on a fresh database, which looks like a broken feature rather than an empty one.

# 4. frontend
cd "C:\BMP -PRJ\BMP-FE"    ; npx expo start --web --port 19000
cd "C:\BMP -PRJ\BMP-ADMIN" ; npm run dev
```

---

## Does the seed need to run again?

**`dev-seed.sql` — no.** Your salons already exist. It's `ON CONFLICT DO NOTHING`, so re-running is
harmless but pointless.

**`dev-seed-discovery.sql` — YES, once.** It's new in Session 48 and has never run on your database.
Without it every salon has `area = NULL`, no categories and no photos, which is why area search
returned nothing and every card said "0 verified reviews". It is written as `UPDATE` +
`ON CONFLICT DO NOTHING`, so running it twice changes nothing the second time.

Re-run it any time you `docker compose down -v` (that wipes the volume) or add salons you want
discovery data for.

---

## THE ONE THAT KEEPS BITING: restart ≠ rebuild

Session 48 lost a full test cycle to this, so it goes first.

Restarting a service that was started from a JAR or from IntelliJ's run configuration **does not
recompile your Java**. The service comes up cleanly, logs normally, and serves the previous build.
There is no error anywhere — you just keep seeing behaviour you already fixed.

The tell: source file is newer than its `.class`.

```powershell
cd "C:\BMP -PRJ\BMP"
Get-Item bmp-notification\src\main\java\com\bmp\notification\services\NotificationDispatcher.java |
    Select-Object LastWriteTime
Get-Item bmp-notification\target\classes\com\bmp\notification\services\NotificationDispatcher.class |
    Select-Object LastWriteTime
```

If the `.java` is newer, the running service is stale. `mvn spring-boot:run` (which
`run-service.ps1` uses) always recompiles first, so prefer it over re-running a JAR.

To force a clean rebuild of everything:

```powershell
cd "C:\BMP -PRJ\BMP" ; mvn clean install -DskipTests
```

**Config files count as build output too.** `application.yml` is copied into `target/classes` at
build time — so a change to `public-paths` needs a rebuild, not just a restart. That specific one
cost us a session: the public salon page kept returning 401 because the built copy still had the
old list.

---

## Start order, and why it matters

| # | What | Port | Why here |
|---|------|------|----------|
| 1 | `docker compose up -d` | 5432 / 9092 / 9000 | Postgres, Kafka, MinIO. Everything else needs these. |
| 2 | `eureka-server` | 8761 | Service registry. Start it first or the others log registration failures for a minute. |
| 3 | `bmp-config-server` | 8888 | Optional — every service imports it as `optional:` and starts fine without it. |
| 4 | `bmp-auth` | 8081 | Login/OTP. Nothing else can be used without it. |
| 5 | `bmp-user` | 8082 | Profiles; bmp-salon calls it to resolve owner contact details. |
| 6 | `bmp-salon` | 8083 | Salons, services, availability. **Runs the salon_schema migrations — must be up before the seed.** |
| 7 | `bmp-booking` | 8084 | Bookings. |
| 8 | `bmp-admin` | 8088 | Moderation queue + console API. |
| 9 | `bmp-notification` | 8089 | Email. Start it before testing any flow that sends one. |
| 10 | `api-gateway` | 8080 | **Last.** It routes to the others; starting it first just means a window of 503s. |

`bmp-payment` (8085), `bmp-rewards` (8087) and `bmp-monitoring` (8090) are only needed for their own
features.

**`bmp-review` (8086) stopped being optional in Sessions 60–61.** Three staff-side flows call it now,
and each one fails in a way that looks like a different bug if it isn't running:

| If bmp-review is down | What you see |
|---|---|
| Upholding a content report | the decision saves, the review stays visible, and only the log says why |
| Generating a DPDP data export | the bundle comes back with `problems: ["We couldn't retrieve reviews you wrote."]` |
| A customer reporting a salon | unaffected — reports land in bmp-admin |

The first is the one worth knowing about: the moderation screen reports success because the
DECISION succeeded. That is deliberate (the decision is recorded and retryable) but it means "I
upheld it and nothing happened" is a *bmp-review is down* symptom, not a moderation bug.

Order matters far less than it looks — Eureka retries and the config import is optional — but
following it means a clean log instead of two minutes of connection errors you then have to read
past.

---

## Email

Email is **off by default**. `bmp.notification.email-provider` defaults to `log`, so every message
prints to the console and nothing is sent — signup looks like it worked and no OTP arrives.

To send for real, `BMP\local-secrets.properties` must exist (copy `local-secrets.example.properties`
and fill in the four values). Spring reads it whatever launched the JVM.

**Do not rely on `local-secrets.ps1` alone.** It sets PowerShell `$env:` variables, and IntelliJ run
configurations do not inherit them — that is a different way to end up with a service that silently
logs instead of sends.

Confirm at startup. `bmp-notification` prints one of:

```
SMTP OK — connected and authenticated to smtp.gmail.com:587 as ...   <- real delivery
EMAIL IS IN LOG-ONLY MODE — no real emails will be sent.             <- nothing will arrive
```

`local-secrets.properties` and `local-secrets.ps1` are **gitignored. Never commit either.**

---

## Frontend

```powershell
cd "C:\BMP -PRJ\BMP-FE"
npx expo start --web --port 19000     # http://localhost:19000
```

Metro hot-reloads, so frontend changes need no restart — only a browser refresh. Restart Metro if
you change `app.json`, `metro.config.js`, `tsconfig.json`, or install a package.

`EXPO_PUBLIC_USE_MOCKS` defaults to **true**. With mocks on, the app never calls your backend, so
none of the above matters and nothing you change server-side will show. Set it to `false` in
`BMP-FE\.env` to test against real services.

Console:

```powershell
cd "C:\BMP -PRJ\BMP-ADMIN" ; npm run dev
```

---

## After a `docker compose down -v`

`-v` deletes the volume, so the database is empty. Full path back:

1. `docker compose up -d`
2. Start `bmp-auth`, `bmp-user`, `bmp-salon`, `bmp-admin` — each runs its own Flyway migrations on
   boot, into its own schema with its own history table.
3. `psql ... -f seed\dev-seed.sql`
4. `psql ... -f seed\dev-seed-discovery.sql`

Running the seeds before the services have migrated fails with "relation does not exist" — the
tables genuinely aren't there yet.

---

## When something looks broken, check these first

- **A fix isn't taking effect** → the service wasn't rebuilt. Compare the `.java` and `.class`
  timestamps above. This is the single most common cause.
- **No email arrives** → look for `EMAIL IS IN LOG-ONLY MODE` in the bmp-notification startup log.
- **App shows old//fake data** → `EXPO_PUBLIC_USE_MOCKS` is still `true`.
- **Search finds nothing by area** → `dev-seed-discovery.sql` hasn't been run on this database.
- **Salon page says it can't load** → read the message. It now names the cause: a sign-in complaint
  means a `public-paths` problem (rebuild bmp-salon), "couldn't reach BMP" means the service or the
  gateway is down.
- **Owner dashboard says "no salon attached"** → the JWT predates the salon. Fixed in Session 48 by
  re-minting the token after creation; if you see it on an account created earlier, sign out and in.
