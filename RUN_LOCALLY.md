# BMP Platform — Local Setup & Run Guide

This is the step-by-step for getting the full BMP microservices stack running on your
own laptop: build once, start infra, start every service in the right order, log in,
and call an authenticated endpoint. Follow it top to bottom the first time.

13 independently-runnable Spring Boot services (9 business services + auth + Eureka +
API Gateway + Config Server + a monitoring dashboard), one shared PostgreSQL
(schema-per-service), Redis, Kafka.

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

The repo's `docker-compose.yml` (root of the repo) defines exactly **3 containers** —
this is infrastructure only, not the app itself (see §3d for why that matters):

| Container | Image | Port | What it's for | Credentials |
|---|---|---|---|---|
| `bmp-postgres-1` | `postgis/postgis:16-3.4` | 5432 | The one shared Postgres — every service gets its own schema in the same `bmp` database (e.g. `user_schema`, `salon_schema`, `booking_schema`...). PostGIS extension is bundled though not actively used by app code yet. | db `bmp`, user `bmp`, password `devonly` |
| `bmp-redis-1` | `redis:7-alpine` | 6379 | Short-lived slot locks during booking (5-minute holds) — not a general cache. | none |
| `bmp-kafka-1` | `apache/kafka:3.8.0` (KRaft mode, no Zookeeper needed) | 9092 | Backs two things: the transactional-outbox relay (every service's domain events flow through here to `bmp-notification`) and Spring Cloud Bus (config-server-driven `/actuator/busrefresh`). | none |

Postgres data persists in a named Docker volume (`bmp_pgdata`) across `docker compose
down`/`up` cycles — it survives stopping the containers, but not `docker compose down
-v` (see the reset note below). Redis and Kafka have no persistent volume: their data
is disposable and always starts empty.

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

There is **no fixed username/password**. Login is phone-number + OTP, handled entirely
by `bmp-auth` (port 8081) — but for local dev there's a **static dev OTP** so you don't
need any real SMS/WhatsApp/email provider (not wired up yet — see §9), and don't need
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

**`000000` always works**, for any phone number, in local dev — it's a fixed bypass
(`bmp.auth.dev-master-otp` in `bmp-auth`'s `application.yml`, defaulting to `000000`,
overridable via the `BMP_DEV_MASTER_OTP` env var). It's only active on the default
profile (what runs with no `SPRING_PROFILES_ACTIVE` set, i.e. every local machine) —
staging/prod profiles don't set it, so it's disabled there. You still need to have
called Step 1 first for that phone number (an OTP request record has to exist), you
just don't need to know the *real* generated code.

<details>
<summary>Prefer the real generated code instead? (click to expand)</summary>

Look at the **bmp-notification** terminal/log output after Step 1, for a line like:

```
[SMS STUB — no real gateway configured] to=+919876543210 message="Your BMP OTP is 482913..."
```

That 6-digit code works too (it's also logged a second time on the `[EMAIL STUB]`
line) — same 5-minute expiry / 3-attempt lockout rules apply to it, unlike the static
`000000` bypass which always works regardless of expiry/attempts.
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

Most endpoints across most services are currently wide open in local dev (no token
required) — only `bmp-auth`, **`bmp-user` (tightened in Session 13: every
`/api/v1/users/**` call now needs a bearer token or the internal service key — end
users can only access their own record)**, and any endpoint explicitly annotated with
`@PreAuthorize` enforce a real check right now. That's an intentional, tracked interim
state (see each service's `bmp.security.public-paths` in its `application.yml`), not a
bug — the remaining services get the same treatment in the ongoing service-by-service
pass.

---

## 9. What's stubbed / not configured yet

These are deliberately left as dev-only placeholders — don't spend time debugging
"why doesn't email/SMS/payment actually work," it's not wired up yet:

- **SMS/WhatsApp**: logged to console only (`LoggingSmsSender`) — see §7.
- **Email**: logged to console only (`LoggingEmailSender`), and `spring.mail.host`
  points at a `localhost:1025` placeholder that isn't running.
- **Razorpay** (bmp-payment): no real API keys configured.
- **Google Sign-In** (`POST /api/v1/auth/oauth2/google`): returns `501` until
  `BMP_GOOGLE_CLIENT_ID` is set — no Google Cloud OAuth client exists yet.

These will be configured service-by-service later; local dev and Swagger testing work
fine without them.

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
