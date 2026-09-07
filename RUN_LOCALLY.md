# BMP Platform — Local Setup & Run Guide

This is the step-by-step for getting the full BMP microservices stack running on your
own laptop: build once, start infra, start every service in the right order, log in,
and call an authenticated endpoint. Follow it top to bottom the first time.

13 independently-runnable Spring Boot services (9 business services + auth + Eureka +
API Gateway + Config Server + a monitoring dashboard), one shared PostgreSQL
(schema-per-service), Redis, Kafka.

---

## 0. There are THREE repositories

New here? Read this first — the backend alone doesn't give you anything to look at.

| Repo | What it is | Runs on | Its own guide |
|---|---|---|---|
| **BMP** (this one) | The backend. 13 Spring Boot services. | 8080–8090, 8761, 8888 | this file |
| **BMP-FE** | The customer app — web, Android, iOS from one Expo codebase. Customers, salon owners, managers, stylists. | 19000 (pass `--port 19000`) | `../BMP-FE/RUN_LOCALLY.md` |
| **BMP-ADMIN** | The internal staff console — ops, support, moderation. BMP employees only. | 5180 | `../BMP-ADMIN/RUN_LOCALLY.md` |

Clone them as **siblings** — the docs cross-reference each other with `../` paths:

```
BMP -PRJ/
├── BMP/
├── BMP-FE/
└── BMP-ADMIN/
```

**Both frontends run on mock data by default and need no backend at all.** If you're doing UI
work, start there and skip this entire document. You only need the backend running when you're
changing something that crosses the API boundary.

Who to sign in as, in every app: **`docs/TEST_CREDENTIALS.md`**.

---

## 0b. THE FAST PATH — you have done this before and just want it running

Everything below this section is the careful first-time walkthrough. If your machine is already
set up, this is the whole thing:

```powershell
cd C:\BMP -PRJ\BMP

docker compose up -d                       # postgres, redis, kafka, minio
mvn -q -DskipTests install                 # ~2 min first time, seconds after
.\run-service.ps1 eureka-server            # wait for it to say "Started"
.\run-service.ps1 bmp-config-server
.\run-service.ps1 api-gateway
.\run-service.ps1 bmp-auth                 # then the rest, any order
```

**Order matters for exactly three of them** — Eureka, Config Server, Gateway — because everything
else registers with Eureka on startup. The nine business services can start in any order after
that, and can be started only as you need them: a customer-booking change needs
`bmp-user`, `bmp-salon`, `bmp-booking` and nothing else.

Use **`.\run-service.ps1 <name>`** rather than `mvn spring-boot:run` directly. It dot-sources
`local-secrets.ps1` into the same shell that starts the service — PowerShell's `$env:X` only
affects the current shell and its children, so setting secrets in one terminal and starting the
service from another (or from the IDE's run button) leaves the service with none of them. Three
sessions were lost to that before this script existed.

Then, in two more terminals:

```powershell
cd ..\BMP-FE    ; npm install ; npm start     # http://localhost:19000
cd ..\BMP-ADMIN ; npm install ; npm run dev   # http://localhost:5180
```

**Not sure something is running?**

```powershell
docker compose ps                                  # 4 containers, all "Up"
curl http://localhost:8761                         # Eureka dashboard — lists every registered service
curl http://localhost:8080/actuator/health         # gateway
```

If a service is missing from the Eureka list, it either failed to start or cannot reach Eureka —
its own terminal window has the reason. **Read that window rather than restarting**; the two
commonest causes (wrong JDK, Postgres not up) both print a clear line and both survive a restart.

---

## 0c. Everything you must install, in one table

Detail and download links are in §1; this is the checklist and how to prove each one works.

| Software | Version | Needed by | Verify with |
|---|---|---|---|
| **JDK** (Temurin) | **21 exactly** | BMP backend | `java -version` → `21.x` |
| **Maven** | 3.9+ | BMP backend | `mvn -v` → also check it reports Java 21 |
| **Docker Desktop** | any recent | BMP backend | `docker compose version` |
| **Node.js** | **20 LTS or newer** | BMP-FE, BMP-ADMIN | `node -v` |
| **npm** | ships with Node | BMP-FE, BMP-ADMIN | `npm -v` |
| **Git** | any recent | all three | `git --version` |

Optional but useful:

| Software | Why |
|---|---|
| **IntelliJ IDEA** | Best multi-module Maven + Spring Boot support. VS Code + "Extension Pack for Java" works too. |
| **psql** | Not required — `docker exec bmp-postgres-1 psql -U bmp -d bmp` gets you a shell inside the container without installing anything. |
| **Postman** | Swagger UI at `http://localhost:<port>/swagger-ui.html` covers most of it. |
| **Expo Go** (phone app) | Only to run BMP-FE on a real phone. The web target needs nothing extra. |

**No global npm installs are required.** Expo and Vite both come from each repo's own
`package.json`, so `npm install` in the repo is the entire setup — do NOT `npm i -g expo-cli`,
which is the deprecated tool and conflicts with the local one.

**JDK 21 EXACTLY, not 17 and not 22.** The root `pom.xml` sets `java.version=21` and
`maven.compiler.release=21`. On an older JDK the build fails with a release-version error; on a
newer one Lombok's annotation processing can break in ways whose message names neither Lombok nor
the JDK. If you have several JDKs installed, set `JAVA_HOME` explicitly — see the end of §1.

---

## 1. Required tools & versions

Install these before doing anything else. Versions matter — an older JDK or an IDE
without recent Java support will fail in confusing ways.

| Tool | Version | Notes |
|---|---|---|
| JDK | **21** (exactly, not 17) | Project targets `java.version=21` in the root `pom.xml`. [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21) is what this was built/tested against — pick the JDK (not JRE) installer for your OS. |
| Maven | **3.9+** | `mvn -v` to check. [Install guide](https://maven.apache.org/install.html) if you don't have it, or use the wrapper if one gets added later. |
| Docker Desktop | any recent | Runs Postgres (PostGIS), Redis, Kafka via `docker-compose.yml`. Must be **running** (whale icon steady in the tray) before you start any service. [Download](https://www.docker.com/products/docker-desktop/). |
| Git | any recent | To clone/pull. |
| IDE | IntelliJ IDEA (Community or Ultimate) recommended | This is what the project was developed in — best Spring Boot/Maven multi-module support. VS Code with the "Extension Pack for Java" + "Spring Boot Extension Pack" also works. Either way, point the IDE's Project SDK at your JDK 21 install, not whatever it defaults to. |
| Postman or `curl` | any | For calling endpoints outside Swagger. Examples in this doc use `curl` via PowerShell. |

Windows-specific: if you also have older JDKs installed (8/11/17 are common), don't
just install JDK 21 alongside them and hope — explicitly set `JAVA_HOME` for this
project so Maven picks the right one:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -v   # confirm it reports "Java version: 21..." before doing anything else
```

Put those two `$env:` lines in every new terminal you use for this project (or set
`JAVA_HOME` permanently via *System Properties → Environment Variables*, and make sure
it's ordered before any older JDK on `PATH`).

---

## 2. Clone the repo & build once

```powershell
git clone https://github.com/devadminbmp/BMP.git BMP
cd BMP
git checkout <branch-you-were-told-to-use>   # e.g. main, or a feature/* branch
mvn -DskipTests install
```

If you already have it cloned and are just pulling the latest changes:

```powershell
cd BMP
git pull
mvn -DskipTests install
```

This builds all 14 Maven modules (`bmp-common` + 13 services) in the right dependency
order and installs them to your local `.m2` repo. Expect **BUILD SUCCESS** with a
Reactor Summary listing all 14 modules. If anything fails here, fix it before moving on
— nothing downstream will work with a broken build.

You only need to re-run this after pulling changes that touch `.java`/`pom.xml` files.
Changing only an `application.yml` does not require a rebuild.

### 2b. Why it must be `install`, and why you must restart afterwards

**`install`, not `package`.** `mvn -pl bmp-auth spring-boot:run` resolves `bmp-common` from your
local `.m2` repository — **not** from the sibling folder on disk. `package` writes
`bmp-common/target/…jar` and stops; `.m2` keeps whatever was there before. So a service can
happily start against a copy of `bmp-common` that is weeks old.

**This bites more often than it sounds like it should.** `bmp-common` has changed in five recent
sessions — four new domain events, a security filter, `Money`. Every one of those is a class some
other service loads.

**And restart the service afterwards.** A JVM resolves classes *lazily*, on first use. If
`bmp-auth` was started while `bmp-common/target/classes` was mid-rebuild — which is exactly what a
failed build leaves behind — it starts fine, serves most requests fine, and then throws on the one
request that first touches a class that wasn't there yet:

```
Handler dispatch failed: java.lang.NoClassDefFoundError: com/bmp/common/ids/UuidV7
```

The slash-separated name is the tell: that is the classloader saying *not found*. A class that was
found but whose static initialiser blew up gives you *"Could not initialize class
com.bmp.common.ids.UuidV7"* instead — a different problem with a different fix.

Session 42 hit exactly this: a build failed at 19:14 and wiped `bmp-common/target/classes`, the
good build landed at 19:29, but `bmp-auth` had been started in between and kept its stale view
until it was restarted.

**The safe sequence after any `bmp-common` change:**

```powershell
mvn -DskipTests install          # from the repo root, not from a module
# then stop and restart every service you already had running
```

---

## 3. Configure & start infrastructure (Docker: Postgres, Redis, Kafka)

### 3a. Install & configure Docker Desktop

1. Download and install [Docker Desktop](https://www.docker.com/products/docker-desktop/)
   for your OS.
2. **Windows**: Docker Desktop needs the WSL2 backend. The installer asks for this and
   will prompt you to install/update WSL2 if it's missing — accept it, then reboot if
   asked. (Settings → General → "Use the WSL 2 based engine" should end up checked.)
3. **Resource allocation**: Settings → Resources — give it at least 4GB RAM. Default is
   usually fine; only an issue if your machine is already tight on memory (you'll also
   be running up to 13 JVMs alongside these containers — see §12 if things get slow).
4. **Start Docker Desktop** and wait for the whale icon in your system tray to go
   steady/stop animating — that's the engine actually ready, not just the window open.
   Confirm from a terminal:

   ```powershell
   docker --version
   docker compose version
   docker info      # errors out if the engine isn't actually up yet
   ```

You need Docker Desktop **running** (not just installed) every time before you start
any BMP service, since every service connects to Postgres on startup.

### 3b. What `docker-compose.yml` actually gives you

The repo's `docker-compose.yml` (root of the repo) defines **4 containers** — this is
infrastructure only, not the app itself (see §3d for why that matters):

| Container | Image | Port | What it's for | Credentials |
|---|---|---|---|---|
| `bmp-postgres-1` | `postgis/postgis:16-3.4` | 5432 | The one shared Postgres — every service gets its own schema in the same `bmp` database (e.g. `user_schema`, `salon_schema`, `booking_schema`...). PostGIS extension is bundled though not actively used by app code yet. | db `bmp`, user `bmp`, password `devonly` |
| `bmp-redis-1` | `redis:7-alpine` | 6379 | Short-lived slot locks during booking (5-minute holds) — not a general cache. | none |
| `bmp-kafka-1` | `apache/kafka:3.8.0` (KRaft mode, no Zookeeper needed) | 9092 | Backs two things: the transactional-outbox relay (every service's domain events flow through here to `bmp-notification`) and Spring Cloud Bus (config-server-driven `/actuator/busrefresh`). | none |
| `bmp-minio-1` | `minio/minio` | 9000 API, 9001 console | S3-compatible object storage for uploaded images — salon photos, service photos, salon cover. `bmp-salon` writes here. **Added in Session 44; this table said "3 containers" until Session 68, so anyone who followed the old guide had image upload fail with a connection error and nothing explaining it.** Browse what's stored at `http://localhost:9001`. | user `bmp-dev`, password `devonly-minio-password` |

Postgres data persists in a named Docker volume (`bmp_pgdata`) across `docker compose
down`/`up` cycles — it survives stopping the containers, but not `docker compose down
-v` (see the reset note below). MinIO likewise persists in `bmp_miniodata`. Redis and Kafka have
no persistent volume: their data is disposable and always starts empty.

### 3c. Start it

From the repo root, with Docker Desktop running:

```powershell
docker compose up -d
docker compose ps
```

You should see all 3 containers `Up`: `bmp-postgres-1`, `bmp-redis-1`, `bmp-kafka-1`.
First run pulls the images (~1-2 min depending on connection); after that it's seconds.

Postgres comes up with an **empty** database — every service creates its own schema
and runs its own Flyway migrations independently the first time it starts (§4). You
don't need to run any SQL by hand.

**Verify each container individually** if you want more than "container status says
Up" (useful when debugging a connection issue):

```powershell
# Postgres — lists every service's schema; empty/missing schemas mean that service
# hasn't started successfully yet, not a Postgres problem.
docker exec bmp-postgres-1 psql -U bmp -d bmp -c "\dn"

# Redis — should print PONG
docker exec bmp-redis-1 redis-cli ping

# Kafka — lists topics; should include bmp.events and springCloudBus once at least
# one service has started (topics are created on first use, not at container boot)
docker exec bmp-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

> **Resetting local data**: if your local DB ever gets into a bad state (e.g. a Flyway
> migration checksum mismatch, or you just want a clean slate), `docker compose down -v`
> wipes the volume, then `docker compose up -d` recreates it empty. Every service will
> re-run its migrations from scratch on next start. Only do this for your own local dev
> data — never against a shared/staging database.

### 3d. Why can't we just run everything with `docker-compose.yml`?

Short answer: **`docker-compose.yml` only defines infrastructure (Postgres/Redis/
Kafka) — none of the 13 Spring Boot services are in it.** Running `docker compose up`
alone gets you a database, a cache, and a broker with *nothing* connected to them —
nothing listening on 8080-8090, no Eureka, no APIs. You still have to start every
service yourself via `mvn spring-boot:run` (§4); that step is not optional and can't be
skipped by adding more to this file, because those Docker images/Dockerfiles for the 13
services don't exist yet.

This is on purpose for now, not an oversight:

- **Faster inner dev loop.** Recompiling a JVM process and re-running `mvn
  spring-boot:run` is much faster than rebuilding a Docker image and restarting a
  container on every code change — better for active development.
- **No Dockerfiles exist yet** for `bmp-auth`, `bmp-user`, etc. — containerizing the
  services themselves (and adding them to this compose file) is future work someone
  would need to explicitly build, not something you're missing a flag for today.

One more thing worth knowing: the comment at the very top of `docker-compose.yml` still
says *"the entire BMP backend is `docker compose up` + `mvn spring-boot:run -pl
bmp-app`"* — that's **stale**, left over from before the Session 5 microservices pivot,
when there was a single `bmp-app` monolith instead of 13 separate services (see
`bmp-app/RETIRED.md`). It hasn't been updated to reflect that you now run 13 services
individually (§4), not one. Don't follow that comment literally.

---

## 4. Start the services — in this order

Each service is its own Spring Boot app on its own port. **Order matters** for a clean
first boot: Eureka and Config Server first (everything else registers with/reads from
them, though both are soft dependencies — services still start without them, just
without service discovery / hot config), then the rest in any order.

Open one terminal per service (or use the background-job pattern in [§4b](#4b-run-everything-from-one-terminal-optional) below), `cd` to the repo root, set `JAVA_HOME`/`PATH` as in §1, then:

| # | Service | Port | Command |
|---|---|---|---|
| 1 | eureka-server | 8761 | `mvn -pl eureka-server spring-boot:run` |
| 2 | bmp-config-server | 8888 | `mvn -pl bmp-config-server spring-boot:run` |
| 3 | bmp-auth | 8081 | `mvn -pl bmp-auth spring-boot:run` |
| 4 | bmp-user | 8082 | `mvn -pl bmp-user spring-boot:run` |
| 5 | bmp-salon | 8083 | `mvn -pl bmp-salon spring-boot:run` |
| 6 | bmp-booking | 8084 | `mvn -pl bmp-booking spring-boot:run` |
| 7 | bmp-payment | 8085 | `mvn -pl bmp-payment spring-boot:run` |
| 8 | bmp-review | 8086 | `mvn -pl bmp-review spring-boot:run` |
| 9 | bmp-rewards | 8087 | `mvn -pl bmp-rewards spring-boot:run` |
| 10 | bmp-admin | 8088 | `mvn -pl bmp-admin spring-boot:run` |
| 11 | bmp-notification | 8089 | `mvn -pl bmp-notification spring-boot:run` |
| 12 | bmp-monitoring | 8090 | `mvn -pl bmp-monitoring spring-boot:run` |
| 13 | api-gateway | 8080 | `mvn -pl api-gateway spring-boot:run` |

Wait for each one to print `Started <X>Application in N seconds` before assuming it's
up — or just poll the health check (§5).

Running all 13 at once is memory-hungry (13 JVMs). If your laptop struggles, add a
capped heap to each command: `-Dspring-boot.run.jvmArguments="-Xmx256m"`. You don't
need every service running to work on any one of them — e.g. if you're only touching
`bmp-salon`, you realistically need eureka + config-server + bmp-salon (+ whatever it
calls via Feign: bmp-user for owner lookups).

### 4b. Run everything from one terminal (optional)

If you'd rather not open 13 terminal tabs, this PowerShell snippet launches all of them
as background jobs from one window (run from the repo root, after §1's `JAVA_HOME`
setup and after `docker compose up -d`):

```powershell
$services = @(
  "eureka-server","bmp-config-server","bmp-auth","bmp-user","bmp-salon",
  "bmp-booking","bmp-payment","bmp-review","bmp-rewards","bmp-admin",
  "bmp-notification","bmp-monitoring","api-gateway"
)
foreach ($s in $services) {
  Start-Job -Name $s -ScriptBlock {
    param($svc, $javaHome)
    $env:JAVA_HOME = $javaHome
    $env:PATH = "$javaHome\bin;$env:PATH"
    Set-Location $using:PWD
    mvn -q -pl $svc spring-boot:run "-Dspring-boot.run.jvmArguments=-Xmx256m"
  } -ArgumentList $s, $env:JAVA_HOME
  Start-Sleep -Seconds 2   # stagger startup a bit
}
# Check status any time with:  Get-Job
# Tail a specific service's output with:  Receive-Job -Name bmp-auth -Keep
# Stop everything with:  Get-Job | Stop-Job; Get-Job | Remove-Job
```

---

## 5. Verify everything is up

```powershell
$ports = 8761,8888,8080,8081,8082,8083,8084,8085,8086,8087,8088,8089,8090
foreach ($p in $ports) {
  try { $r = Invoke-WebRequest -Uri "http://localhost:$p/actuator/health" -UseBasicParsing -TimeoutSec 3; Write-Output "$p -> $($r.StatusCode)" }
  catch { Write-Output "$p -> DOWN" }
}
```

Every port should report `200`. If one doesn't, check that service's console output —
almost every startup failure so far has been a clear stack trace right at the bottom
(missing config property, schema mismatch, etc.), not a silent hang.

Eureka's own dashboard (useful to see who's registered) is at **http://localhost:8761**.

---

## 5b. Load the seed data

An empty database technically works, but every screen in both frontends is blank and you can't
tell "no data" from "broken query". Load the seed:

```powershell
docker cp seed\dev-seed.sql bmp-postgres-1:/tmp/dev-seed.sql
docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/dev-seed.sql
```

> **Not `psql ... < seed/dev-seed.sql`.** That's a bash redirect; in PowerShell `<` is a reserved
> operator and the line fails with *"The '<' operator is reserved for future use"* before Docker
> is invoked at all. This doc said the wrong thing until Session 43 — under a `powershell` fence,
> which made it look verified. Copying the file in also sidesteps pipe re-encoding, which matters
> because the seed contains `Lumière`.

**Run this AFTER the services have started at least once**, so Flyway has created the schemas.
Running it first fails with "relation does not exist", which is confusing rather than harmful.

Confirm it worked. All three numbers matter:

```powershell
docker exec bmp-postgres-1 psql -U bmp -d bmp -c "
SELECT (SELECT count(*) FROM user_schema.users)          AS users,     -- >= 6
       (SELECT count(*) FROM salon_schema.salon)         AS salons,    -- 8
       (SELECT count(*) FROM salon_schema.salon_service) AS services,  -- 55
       (SELECT count(*) FROM salon_schema.salon_staff)   AS staff;"    -- 3
```

**`staff` is the one people forget.** Those three rows are what tie Kavya, Rahul and Sneha to a
salon. `AuthService.resolveSalonScope()` re-reads `salon_staff` on every token mint, so with no
seat the owner logs in perfectly well and then gets 403 from every salon-scoped endpoint —
their JWT carries `salonId = null`. It presents as "the dashboard is broken", not as "the seed
is incomplete".

If `users` is 0, login fails with **"No account found for this number"** — the correct answer to
the question asked, just not the one you wanted.

### It's genuinely idempotent now (Session 43)

Run it as many times as you like. It also **repairs** the state that used to break it.

The old file claimed idempotency on the strength of `ON CONFLICT (id) DO NOTHING`, which only
protects against re-running *itself*. When the app created an account on a seeded phone — which
it did, because a fail-open user lookup and a too-loose phone validator conspired to sign people
up — that row had a random id. The conflict target never matched, the `uk_users_phone` unique
index did, and since the whole file is one transaction, *every statement after it* failed with
`current transaction is aborted` and the lot rolled back. One stale row, and the seed silently
did nothing.

Fixed three ways:

1. A reclaim step at the top deletes any row squatting a seeded phone under the wrong id
   (children first — `user_roles`, `refresh_tokens`, `onboarding_state` have real FKs). It can
   only ever match the six seeded numbers, so **accounts you created on other numbers survive**.
2. `salon_service` and `salon_staff` inserts no longer reference `updated_at` / `status` —
   columns that have never existed on those tables. The seed had been written against an
   imagined schema, and the users error was failing first and hiding it.
3. Verified against a real PostgreSQL built from all 9 services' migrations: three consecutive
   runs, starting from a database already containing squatter rows.

It's idempotent — every insert is `ON CONFLICT DO NOTHING`, so running it twice is harmless.
Re-run it after every `docker compose down -v`.

You get 6 users (2 customers, 2 salon owners, 1 manager, 1 stylist), 8 salons, 55 services and
7 stylists — the **same ids, names and prices** as the frontend's mock dataset
(`../BMP-FE/src/api/mocks/seed.ts`), so the app looks identical whether it's reading mocks or the
real API. That is the point: a difference you see when you flip `EXPO_PUBLIC_USE_MOCKS` is a
real difference, not noise.

> **Why a file and not just typing data in?** `docker compose down -v` is something you'll do
> every time a migration changes, and it wipes everything. Anything entered by hand is gone.
> This file is the durable, version-controlled copy.

Seeded logins are in `docs/TEST_CREDENTIALS.md` — those six, and only those six, use OTP
`000000`. Any other number gets a real code by email (Session 43).

### 5c. The second seed file — the team, leave and goodwill (Sessions 59–61)

`dev-seed.sql` covers customers, salons, services and availability. It does **not** cover the
staff-side tables added since Session 59, and on a fresh database the console's Team, Leave, Queues,
Goodwill and approvals-matrix screens are all empty — which is indistinguishable from broken.

```powershell
docker cp seed\dev-seed-team.sql bmp-postgres-1:/tmp/dev-seed-team.sql
docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/dev-seed-team.sql
```

Run it **after** `dev-seed.sql`, and after bmp-admin has started at least once so Flyway has built
`admin_schema`. Idempotent and re-runnable, same as the first file.

It creates five colleagues (two support agents, a support lead, an ops admin and finance) alongside
V003's superadmin, gives everyone employment details and a manager, and adds leave in four states,
a queue cap and two goodwill grants.

> **None of those five accounts can be signed into.** They carry the same non-bcrypt placeholder
> V003 uses (`LOCKED-NO-PASSWORD-SET`) — not a weak password, not a hash at all, so verification
> cannot succeed. To actually use one, issue an activation code from the console
> (Staff accounts → reissue), which is the path a real hire goes through.

Confirm:

```powershell
docker exec bmp-postgres-1 psql -U bmp -d bmp -c "
SELECT (SELECT count(*) FROM admin_schema.bmp_staff)      AS staff,     -- 6
       (SELECT count(*) FROM admin_schema.staff_leave)    AS leave,     -- 4
       (SELECT count(*) FROM admin_schema.queue_config)   AS queues,    -- 4
       (SELECT count(*) FROM admin_schema.goodwill_grant) AS goodwill;" -- 2
```

**If `leave` and `goodwill` come back 0 while the command reported success**, the staff rows are
missing — that was a real bug in the first version of this file, which guarded every insert on
staff roles it did not create and so silently inserted nothing. Fixed in Session 61; the check
above exists because "it ran fine and did nothing" is the failure mode worth catching.

---

## 6. Ports & Swagger UI reference

| Service | Port | Swagger UI | OpenAPI JSON |
|---|---|---|---|
| api-gateway | 8080 | — (routes to the services below) | — |
| bmp-auth | 8081 | http://localhost:8081/swagger-ui/index.html | http://localhost:8081/v3/api-docs |
| bmp-user | 8082 | http://localhost:8082/swagger-ui/index.html | http://localhost:8082/v3/api-docs |
| bmp-salon | 8083 | http://localhost:8083/swagger-ui/index.html | http://localhost:8083/v3/api-docs |
| bmp-booking | 8084 | http://localhost:8084/swagger-ui/index.html | http://localhost:8084/v3/api-docs |
| bmp-payment | 8085 | http://localhost:8085/swagger-ui/index.html | http://localhost:8085/v3/api-docs |
| bmp-review | 8086 | http://localhost:8086/swagger-ui/index.html | http://localhost:8086/v3/api-docs |
| bmp-rewards | 8087 | http://localhost:8087/swagger-ui/index.html | http://localhost:8087/v3/api-docs |
| bmp-admin | 8088 | http://localhost:8088/swagger-ui/index.html | http://localhost:8088/v3/api-docs |
| bmp-notification | 8089 | http://localhost:8089/swagger-ui/index.html | http://localhost:8089/v3/api-docs |
| bmp-monitoring | 8090 | — (Spring Boot Admin dashboard, not an API) | — |
| eureka-server | 8761 | — (service registry dashboard) | — |
| bmp-config-server | 8888 | — (config server, no UI) | — |

For day-to-day API testing, hit each service **directly on its own port** (simplest).
`api-gateway` (8080) is the single public entry point that routes `/api/v1/...` paths to
the right service by prefix — use it when you want to test the same routing a real
client would go through (see `api-gateway`'s `application.yml` for the full route table).

---

## 7. Logging in — how to get a bearer token

> **Full credential reference for every role, in all three apps: `docs/TEST_CREDENTIALS.md`.**
> This section covers the customer-side token flow. Staff console accounts work completely
> differently — password + TOTP, a separate table, a separate signing key — see §10b.

There is **no fixed username/password** on the customer side. Login is phone-number + OTP,
handled entirely by `bmp-auth` (port 8081) — but for local dev there's a **static dev OTP** so
you don't need any real SMS/WhatsApp/email provider (not wired up yet — see §9), and don't need
to go dig a code out of a log.

### Step 1 — Request an OTP

```powershell
curl -X POST http://localhost:8081/api/v1/auth/otp/request `
  -H "Content-Type: application/json" `
  -d '{ "phone": "+919876543210", "email": "you@example.com" }'
```

Use **any phone number you like** for testing — different numbers = different test
users. `email` is only required the *first* time a given phone number is seen
(signup); an existing user's stored email is reused automatically after that.

### Step 2 — Verify with the static dev OTP: `000000`

```powershell
curl -X POST http://localhost:8081/api/v1/auth/otp/verify `
  -H "Content-Type: application/json" `
  -d '{ "phone": "+919876543210", "otp": "000000" }'
```

**`000000` works for the six seeded test phones only** — `+919876500001` … `+919876500006`,
listed in `bmp.auth.dev-master-otp-phones`. See `docs/TEST_CREDENTIALS.md`.

> **Changed in Session 43.** It used to work for *any* number. That made it a master key to
> every account on the platform, and it meant the real path — generate → email → read → type —
> was never exercised locally, so the first genuine test of it would have been in front of a
> customer. **Any number outside the list now gets a real emailed code**, which is exactly how
> you should be testing signup.
>
> `AuthService` refuses to start if `dev-master-otp` is set while the allowlist is empty.

The bypass is only active on the default profile (what runs with no `SPRING_PROFILES_ACTIVE`,
i.e. every local machine); staging/prod don't set it. You still need Step 1 first for that
phone — an OTP request record has to exist.

**Codes are single-use** (V005). Verifying spends the code; a second attempt with the same one
returns `410 — This code has already been used`. Run Step 1 again for a fresh code. `000000` is
no exception: a test account is still an account.

<details>
<summary>Using a number that is NOT seeded? Here's where the real code goes. (click to expand)</summary>

**Check your email.** Email is the only channel that actually delivers. Set
`BMP_EMAIL_PROVIDER=smtp` plus `BMP_SMTP_USERNAME` / `BMP_SMTP_PASSWORD` on bmp-notification and
the code arrives in the inbox you supplied at Step 1.

With `BMP_EMAIL_PROVIDER=log` (the default) nothing is sent; the code is printed in the
**bmp-notification** console instead:

```
[EMAIL STUB] to=you@example.com subject="Your BMP verification code" body="Your BMP verification code is 482913..."
```

**SMS and WhatsApp will not show you anything by default.** Both are stubs and both are now
*disabled* (`bmp.notification.channels.sms.enabled` / `...whatsapp.enabled`, default false), so
they log nothing at all — a channel described as off is silent. Set the flag to `true` and the
stub logs what it *would* have sent; it still sends nothing, because SMS is blocked on TRAI DLT
registration and WhatsApp on a Business account plus template approval. Neither is blocked on
code.

Real codes obey the 5-minute expiry and the 5-attempt lockout.
</details>

### Step 3 — Response: your tokens

The `/otp/verify` call above returns:

```json
{
  "userId": "0193...",
  "refreshToken": "selector.verifier",
  "accessToken": "eyJhbGciOi...",
  "expiresIn": 900
}
```

- `accessToken` — a JWT, valid ~15 minutes. This is your **bearer token**.
- `refreshToken` — opaque, valid ~30 days. Exchange it for a new access token via
  `POST /api/v1/auth/refresh` (`{ "refreshToken": "..." }`) instead of logging in again.

New phone number = signup, not login. Optional fields on `/otp/verify` for signup:
`role` (`CUSTOMER` default, or `SALON_OWNER` / `MANAGER` / `STYLIST`), `name`,
`inviteToken` (required for `MANAGER`, obtained from a salon owner's
`POST /api/v1/salons/{salonId}/invites`).

---

## 8. Calling an authenticated endpoint

Add the access token as an `Authorization: Bearer <token>` header on any subsequent
request:

```powershell
$token = "eyJhbGciOi..."   # the accessToken from step 3 above

curl http://localhost:8082/api/v1/users/0193... `
  -H "Authorization: Bearer $token"
```

**In Swagger UI**: open any service's Swagger page (§6), click the **Authorize** 🔒
button top-right, paste the raw access token (no `Bearer ` prefix needed — Swagger adds
it), click Authorize, then "Try it out" on any endpoint.

**Which services actually enforce authorization** (this changed a lot; the old "everything is
open" note was out of date):

| Service | State |
|---|---|
| bmp-auth | Enforced |
| bmp-user | Enforced (Session 13) — you can only read your own record |
| bmp-salon | Enforced on staff/owner endpoints via `@PreAuthorize` |
| bmp-booking | **Enforced (Session 21).** Previously had *no authorization at all* — any customer could read and cancel any booking by id. |
| bmp-rewards | **Enforced (Session 22).** `public-paths` previously fell back to `/**`, i.e. nothing was authenticated. |
| bmp-admin | Enforced, on its own filter chain with a separate key and audience |
| bmp-payment, bmp-review, bmp-notification | Still open — awaiting their own pass |

Each service's `bmp.security.public-paths` in its `application.yml` is the source of truth. Note
the trap that bit twice: the **code default in `CommonSecurityConfig` is `/**`**, so a service
that simply doesn't set the property authenticates *nothing*. An omission fails open. Always set
it explicitly.

---

## 9. What's stubbed / not configured yet

These are deliberately left as dev-only placeholders — don't spend time debugging
"why doesn't email/SMS/payment actually work," it's not wired up yet:

- **SMS/WhatsApp**: logged to console only (`LoggingSmsSender`) — see §7. Still stubbed
  (needs a gateway + India DLT registration).
- **Email**: log-only by default (`LoggingEmailSender`), BUT real SMTP delivery can now be
  switched on (Session 14) — set `BMP_EMAIL_PROVIDER=smtp` plus `BMP_SMTP_USERNAME` /
  `BMP_SMTP_PASSWORD` (Gmail app password, or Brevo/SES). Defaults target Gmail
  (`smtp.gmail.com:587`, STARTTLS). Left at `log` by default so no dev needs SMTP creds.
- **Razorpay** (bmp-payment): no real API keys configured.
- **Google Sign-In** (`POST /api/v1/auth/oauth2/google`): returns `501` until
  `BMP_GOOGLE_CLIENT_ID` is set — no Google Cloud OAuth client exists yet.

These will be configured service-by-service later; local dev and Swagger testing work
fine without them.

### Still stubbed as of Session 61

| Thing | State | Blocked on |
|---|---|---|
| Razorpay create-order and refund | throw without keys; the rest of the money path (webhook → capture → confirm → invoice paid) is real | a Razorpay account |
| Customer-facing pay button | absent — the confirm screen says "pay at the salon", which is honest | the above |
| Push notifications | not built | a decision on FCM/APNs |
| Referral payout | records the referral, pays nothing, and **says so in the response** | deferred by Darshan |
| Notification / consent history in the DPDP export | absent, and the export's own notice says so | bmp-notification does not record per-recipient delivery yet |
| Review reporting | wired end to end (Session 61) with **no button** | the salon page shows a review count, not the reviews |

Everything else a customer, salon or staff member can do is real against a running stack.

> **The rule this table exists to enforce:** a stub that pretends to work is worse than a stub that
> refuses. Each row above either throws, returns 501, or states its own limitation in the response
> — none of them silently succeed.

---

## 10. Other credentials (non-login, ops tools only)

| What | Credentials |
|---|---|
| Postgres | user `bmp` / password `devonly` / db `bmp` (port 5432) |
| bmp-config-server (HTTP Basic) | `bmp-config` / `dev-only-config-password-change-in-real-environment` |
| bmp-monitoring dashboard (HTTP Basic) | `bmp-admin` / `dev-only-monitoring-password-change-in-real-environment` |
| JWT signing secret (internal, not a login) | `dev-only-secret-change-in-real-environment-min-32-bytes-long` |

All of the above are hardcoded **dev-only defaults** baked into each service's
`application.yml` (with env-var overrides available, e.g. `BMP_JWT_SECRET`,
`BMP_DB_PASSWORD`) — fine for local dev, must never be used anywhere real.

---

## 10a. Getting into the console with no authenticator app (Session 61)

Two-factor **cannot be skipped** — `StaffAuthService` routes an un-enrolled account into enrolment
rather than past it, deliberately: a 2FA bypass flag is the thing that eventually ships enabled. So
if you have no authenticator app on your phone, you cannot sign in to your own local console.

`tools/totp.mjs` closes that. It is an authenticator, **not a bypass** — it needs the same shared
secret an app would hold, and computes the same RFC 6238 code.

### 1. Turn on the dev staff accounts and restart bmp-admin

```powershell
$env:BMP_ADMIN_DEV_STAFF = "true"
$env:BMP_ADMIN_DEV_STAFF_PASSWORD = "choose-at-least-16-chars"   # optional; one is generated if unset
mvn -pl bmp-admin spring-boot:run
```

`DevStaffSeeder` refuses to run unless the datasource is on **localhost**, whatever the flag says.
That guard is the one that matters: the `dev` profile in this repo points at a shared Neon branch,
and a profile-gated seeder would have written known-password admin accounts there.

### 2. Read the block it logs

```
============================================================================
DEV STAFF ACCOUNTS SEEDED (6 created) — LOCAL DATABASE ONLY
============================================================================
Password for ALL of the accounts below:
    <your password>

  dev.super@bemyprofessional.in        super_admin
  dev.ops@bemyprofessional.in          ops_admin
  dev.lead@bemyprofessional.in         support_lead
  dev.support@bemyprofessional.in      support_agent
  dev.finance@bemyprofessional.in      finance_admin
  dev.readonly@bemyprofessional.in     read_only

Two-factor: ONE entry covers every account above.
  Base32 secret (paste into tools\totp.mjs if you have no authenticator app):
    <SECRET>
  ...
```

**One password and one TOTP secret for all six**, on purpose: five separate QR codes pushes people
back to using the superadmin for everything, which is the behaviour least-privilege testing exists
to prevent. The secret is generated per run, so nothing reusable leaks into git.

### 3. Get the code

```powershell
node tools\totp.mjs <SECRET>            # once
node tools\totp.mjs <SECRET> --watch    # keeps printing as it rolls
```

It takes the bare Base32 secret **or** the whole `otpauth://` URI. No dependencies — `node:crypto`
only. Verified against all five RFC 6238 SHA-1 test vectors.

> The countdown matters. The server accepts ±1 step so a code lives ~90 seconds, but one shown with
> two seconds left will still be typed too slowly. If it's about to roll, wait for the next one —
> that is the difference between "my code is wrong" and "I was too slow".

### Which door

| Account | Door |
|---|---|
| `dev.super`, `dev.ops` | `http://localhost:5180/admin/login` |
| `dev.lead`, `dev.support`, `dev.finance`, `dev.readonly` | `http://localhost:5180/support/login` |

A support account at the admin door passes **both** factors and is then told plainly it's the wrong
console. That is not a bug — it's the check working, and it is worth seeing once.

> **Local only.** Keeping a TOTP secret where a script can read it defeats the point of a *second*
> factor. Fine for a throwaway secret printed into your own terminal; never paste a real staff
> member's secret into that script.

---

## 10b. The staff console (bmp-admin) — a completely separate login

BMP employees don't log in with a phone and an OTP. They use the console (`BMP-ADMIN` repo,
port 5180) against `bmp-admin` (port 8088), with **password + TOTP two-factor**, a **separate
staff table** (`admin_schema.bmp_staff`), and a **separate JWT signing key**
(`bmp.admin.jwt-secret`, not `bmp.auth.jwt-secret`).

That last part is not redundancy. If staff and customer tokens shared a key, anything able to
forge one could forge the other — compromising the consumer stack would hand over the console.

**There is no default password.** `V003` seeds the superadmin with
`password_hash = 'LOCKED-NO-PASSWORD-SET'`, which is not a bcrypt hash and can only fail
verification. Claim it once:

```powershell
$env:BMP_ADMIN_BOOTSTRAP_EMAIL    = "devadmin.bmp@gmail.com"
$env:BMP_ADMIN_BOOTSTRAP_PASSWORD = "<16+ chars from a password manager>"
mvn -pl bmp-admin spring-boot:run
# then REMOVE both variables
```

`StaffBootstrap` applies it **only if the account still has no usable password**, so the
variables are inert on every later boot and cannot reset a live account. First sign-in forces
2FA enrolment. Every other employee is created from the console and gets a one-time
`BMP-XXXX-XXXX` activation code — the admin never sets or sees their password.

Full walkthrough: `../BMP-ADMIN/RUN_LOCALLY.md`. You do **not** need any of this to explore the
console — it runs on mock data with no backend.

---

## 11. Stopping everything

```powershell
# If you used one terminal per service: Ctrl+C in each.
# If you used the background-job pattern in §4b:
Get-Job | Stop-Job
Get-Job | Remove-Job

# Infra:
docker compose down          # stop containers, keep data
docker compose down -v       # stop containers AND wipe the DB volume
```

---

## 11b. CI — what runs on every push

`.github/workflows/ci.yml`, in each of the three repos. Added Session 33.

| Repo | Job | What it answers |
|---|---|---|
| BMP | `mvn -B -ntp verify` | Do all 14 modules compile, **and do the tests pass?** |
| BMP | public-paths guard | Did a service forget `bmp.security.public-paths`, or set it to `/**`? |
| BMP | **write-auth guard** | Does every POST/PUT/PATCH/DELETE carry `@PreAuthorize`? *(Session 41)* |
| BMP | migration warning | Was an already-released migration edited? (warns, doesn't fail) |
| BMP-FE | `tsc --noEmit` + lint | Does it typecheck? |
| BMP-FE | flag guard | Is `.env` committed? Does `auth.ts` reference `USE_MOCKS`? |
| BMP-ADMIN | `tsc` + `vite build` | Does it typecheck AND build? |
| BMP-ADMIN | demo/route guards | Is the mock-login block still gated? Do dashboard links point at real routes? |

**Why it exists:** between Sessions 26 and 32, roughly thirty Java files were written or edited
— a new Maven dependency, three migrations, two entities, a Feign client — and **none of it was
ever compiled**, because the work happened somewhere with no JDK. The cost isn't the bugs; it's
that they all arrive at once, usually the day before someone needs a demo.

**Session 39: `-DskipTests` is gone.** That flag's comment used to say it was "honest rather than
aspirational" because there were no tests. True when written, false the moment there were — a
comment explaining why something isn't done has a short shelf life.

**46 tests run**, all pure logic (milliseconds, no services):

| File | Protects |
|---|---|
| `CancellationTermsTest` | What a customer is charged, from terms frozen months earlier |
| `BookingStatusTest` | Which state transitions are possible — mostly the impossible ones |
| `MoneyTest` | The half-up rounding rule that multiplies everyone's income |
| `MaskPhoneTest` | The only thing between the salon desk and a customer list |

**What does NOT run:** the generated `*ApplicationTests` context-load checks, excluded by name in
the root pom's surefire config. They need PostgreSQL, Kafka and Eureka; on a runner they'd fail
for want of a database rather than for want of correctness, and **a red build caused by missing
infrastructure is the fastest way to teach a team to ignore red builds.** Delete that exclusion
when Testcontainers exists — don't add a second profile.

**Don't add `continue-on-error` to anything:** a red build people learn to ignore is worse than
no build.

Each non-compiler guard exists because the thing it checks has already gone wrong at least
once. The public-paths one has fired **four times** (bmp-salon, payment, review, notification),
leaving 52 endpoints reachable with no credential. The write-auth guard was added after Session 41
found `PUT /api/v1/reviews/{id}` reachable **with no credential at all** — its path was covered by
a `public-paths` entry written for the GET, and *those patterns are path-only and method-blind*.

Every guard was verified green against the current tree before being committed — a check that
fails on day one gets switched off on day two.

### The guards are tests now (Session 43)

They used to be inline `python3` heredocs in the CI workflow plus two scripts under `scripts/`.
They're JUnit tests in `bmp-common/src/test/java/com/bmp/common/repo/`, so they run under the
`mvn -B -ntp verify` that CI already does — **and on your machine, in your IDE, before the push
rather than after it.** No second toolchain for a team that writes Java and TypeScript.

```powershell
mvn -q -pl bmp-common test
```

| Test | Checks |
|---|---|
| `PublicPathsTest` | every service declares `bmp.security.public-paths`, and none sets `/**` |
| `WriteEndpointAuthTest` | every POST/PUT/PATCH/DELETE carries `@PreAuthorize` |
| `SeedSchemaTest` | every seed INSERT names columns that exist |
| `ApiContractTest` | every Zod schema matches the backend record it parses |

Each class's javadoc carries the incident it exists to prevent — read there, not here.

**`ApiContractTest` reads the sibling `../BMP-FE` checkout.** If it isn't there the test *skips*
rather than fails: a backend-only clone is a legitimate state (CI clones one repo), and failing
a build over a missing sibling repo teaches people to ignore the failure. So it protects you
locally, where both repos exist, and stays quiet in CI, where only one does. **Run it before
changing an API shape on either side** — that's the moment it earns its keep.

It exists because this went wrong **twice**: `getSlots` parsing a shape the server never sent
(Session 38), then four discovery schemas asking for fields that didn't exist in the database at
all (Session 40). Together they meant browse → salon page → pick a stylist → pick a slot had
**never worked against a real backend** — invisible because `USE_MOCKS` defaults ON and the mocks
were written from the client's assumptions rather than the server's contract.

It currently scans `BMP-FE/src/api` only. **BMP-ADMIN's schemas are unchecked**, and that repo has
already had one instance of the same bug.

---

## 12. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `mvn` fails immediately with `release version 21 not supported` | You're on JDK 17. Fix `JAVA_HOME` per §1. |
| `docker compose up -d` errors about a pipe / can't connect to daemon | Docker Desktop isn't running — start it and wait for it to fully come up first. |
| A service fails with `Schema-validation: wrong column type` | Your local Postgres volume has stale data from an older migration. `docker compose down -v && docker compose up -d`, then restart the failing service so Flyway runs clean. |
| A service fails with `Migrations have failed validation` / checksum mismatch | Same fix as above — reset the volume. Don't hand-edit an already-applied migration file; add a new one. |
| Port already in use when starting a service | Something's still listening on that port from a previous run. Find it: `Get-NetTCPConnection -LocalPort <port> -State Listen`, then `Stop-Process -Id <pid> -Force`. |
| Swagger UI loads but the endpoint list is empty / 500s | Make sure you rebuilt (`mvn -DskipTests install`) after pulling — a stale `.class` from before a dependency bump will misbehave. |
| Machine grinds to a halt with everything running | 13 JVMs is heavy. Use `-Xmx256m` per service (§4), or only run the handful of services you're actually working on plus their direct dependencies. |
| `DuplicateKeyException: found duplicate key bmp` on startup | Two top-level `bmp:` blocks in that service's `application.yml`. YAML mappings can't have duplicate keys. Merge them into one — and note the crash is the *good* outcome: YAML doesn't merge per-leaf, so a lenient parser would have let the second block silently replace the first one wholesale. Hit in `bmp-admin`, fixed Session 25. |
| Console (5180) 404s on every request | The gateway needs the `/api/v1/admin/**` route predicate. Missing until Session 25 — pull and restart `api-gateway`. |
| Frontend screens are all empty but nothing errors | You skipped the seed (§5b). |
| `relation "user_schema.users" does not exist` when seeding | You ran the seed before the services created their schemas. Start them once, then seed. |
| `NoClassDefFoundError: com/bmp/common/…` at runtime, on a request (not at startup) | **The running JVM is older than your last build.** See §2b — rebuild with `mvn -DskipTests install`, then **restart** the service. The slash-separated name is the classloader saying "not found"; a broken static initialiser would instead say *"Could not initialize class …"*. Hit in Session 42 after a failed build wiped `bmp-common/target/classes` while `bmp-auth` was running. |
| 100+ `cannot find symbol: method getId()` on an `@Getter` class | Lombok isn't running. Fixed in the root pom (Session 42) via `annotationProcessorPaths`. If it persists, IntelliJ is compiling with its own builder: enable **Settings → Build → Compiler → Annotation Processors**, set **Maven → Runner → Delegate IDE build to Maven**, and check the Project SDK is **21** — Lombok 1.18.36 fails on JDK 24/25 however it's configured. |
| Every service fails with `relation "<table>" already exists` | Something ran `bmp-app` (the retired monolith) against this database — it lays down the Session-5 schema and the real services then abort. `docker compose down -v && docker compose up -d`. Its Flyway is now disabled so it can't recur. |
