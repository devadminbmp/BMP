# Database Setup — Local, Shared Dev, Staging, Prod

How the 3 of us (Darshan, Shivam, Achyuth) work against Postgres and MongoDB at the same time
without stepping on each other, and how each environment is wired.

---

## The short version

- **Local** — each of us runs our own Postgres/Mongo via `docker compose up`. Fully offline,
  zero setup, nothing shared. Good for quick iteration on one service.
- **Shared dev** — one Neon Postgres project (`bmp-dev`), each of us gets our own **branch**
  (instant copy-on-write clone of the same schema/data) so we can work concurrently without
  colliding. One shared MongoDB Atlas free cluster, separate database name per person if needed.
- **Staging / Prod** — separate Neon project + separate Atlas cluster, real secrets, not shared
  with dev. Set up later, same mechanism (env vars), just pointed elsewhere.

Every service reads its connection details from environment variables with local defaults baked
in — **nothing needs to change in `application.yml` to switch environments.** Just set env vars
before running the service.

**Where the per-environment files actually are:** every service has 5 files in
`src/main/resources/`:
- `application.yml` — always loaded, common settings + local defaults (same as `application-local.yml`)
- `application-local.yml` — explicit local Docker values, no env vars
- `application-dev.yml` — shared Neon/Atlas, reads the env vars below, no local fallback (fails loudly if you forget to export them)
- `application-staging.yml`, `application-prod.yml` — skeletons, not yet provisioned, same env-var pattern

Pick a profile by setting `SPRING_PROFILES_ACTIVE=local|dev|staging|prod` before running a
service (e.g. `SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run -pl bmp-user`). No profile set =
`application.yml`'s defaults = same as `local`.

---

## Environment variables every service understands

| Variable | Default (local) | What it's for |
|---|---|---|
| `BMP_DB_HOST` | `localhost` | Postgres host |
| `BMP_DB_PORT` | `5432` | Postgres port |
| `BMP_DB_NAME` | `bmp` | Postgres database name |
| `BMP_DB_USER` | `bmp` | Postgres username |
| `BMP_DB_PASSWORD` | `devonly` | Postgres password |
| `BMP_DB_PARAMS` | *(empty)* | Extra JDBC params, e.g. `?sslmode=require` — **Neon requires this** |
| `BMP_DB_POOL_SIZE` | `3` | Max HikariCP connections per service. Kept low on purpose — see below. |
| `BMP_EUREKA_URL` | `http://localhost:8761/eureka/` | Where each service registers itself |
| `BMP_MONGO_URI` | *(not yet wired — bmp-review is the only module that will need it, for the community-feed bridge; add when that ticket starts)* | MongoDB connection string |

These live in every business service's `application.yml` (`bmp-user`, `bmp-salon`, `bmp-booking`,
`bmp-payment`, `bmp-review`, `bmp-rewards`, `bmp-admin`, `bmp-notification`, `bmp-auth`) and in
`api-gateway`'s `BMP_EUREKA_URL`.

**Why the pool size is capped at 3, not Spring's default of 10:** free-tier cloud Postgres caps
total connections (Supabase: 60 direct / 200 pooled; Neon similar via its pooled endpoint). With
9 services each needing a connection and 3 of us potentially running the whole stack at once,
that's up to 27 concurrent pools — at the default of 10 each that's 270 connections, well past
any free tier. At 3 each, it's a maximum of 27, which fits comfortably.

---

## Step 1 — Shared dev Postgres (Neon)

Neon was chosen over Supabase specifically for **branching**: one project holds the canonical
schema, and each of us works on an isolated, instant copy without needing our own Postgres
server or stepping on each other's data.

1. Whoever sets this up first creates a **free Neon account** (neon.com) — no card required —
   and a project called `bmp-dev`.
2. Run the Flyway migrations once against the project's default (`main`) branch to get the
   baseline schema in place (`common_schema` + all 9 module schemas).
3. Each of us creates our **own branch** off `main` from the Neon console (or `neonctl branches
   create`) — e.g. `darshan-dev`, `shivam-dev`, `achyuth-dev`. Branching is instant and free
   (unlimited branches on the free plan).
4. Each of us grabs our branch's connection string from the Neon console and sets these env
   vars locally before running any service:

   ```bash
   export BMP_DB_HOST=<your-branch-host>.neon.tech
   export BMP_DB_PORT=5432
   export BMP_DB_NAME=bmp
   export BMP_DB_USER=<your-neon-user>
   export BMP_DB_PASSWORD=<your-neon-password>
   export BMP_DB_PARAMS="?sslmode=require"
   ```

   (Neon requires SSL — that's what `BMP_DB_PARAMS` is for.)

5. Run any service normally (`mvn spring-boot:run -pl bmp-user`, etc.) — it now talks to your
   personal branch instead of local Docker Postgres.
6. When you want to test against what the others are seeing (integration testing, not just your
   own feature), point at the shared `main` branch's connection string instead — coordinate in
   the group chat before doing this so migrations/data don't collide mid-test.

**Free tier limits to know:** 0.5GB storage per project, 100 compute-hours/month per project,
history window of 6 hours. This is generous for 3 people doing normal dev work — if you ever see
"project suspended" it means the monthly compute-hour budget ran out; it resets next cycle.

---

## Step 2 — Shared dev MongoDB (Atlas)

MongoDB is only used for the community-feed bridge (Module 5: Review) — not needed until that
ticket starts, but setting it up now costs nothing.

1. Create a **free MongoDB Atlas account** (mongodb.com/atlas) and one **M0 free cluster**
   (512MB storage, always-on, free forever, no card).
2. Unlike Postgres, one Mongo cluster can hold many separate databases for free — no branching
   needed. Create one database per person if you want full isolation (`bmp_darshan`,
   `bmp_shivam`, `bmp_achyuth`), or just share `bmp_dev` if collision risk is low (community
   posts are additive, not usually edited concurrently).
3. Set `BMP_MONGO_URI` to your Atlas connection string when that integration is actually wired
   into `bmp-review`.

---

## Step 3 — Local (no cloud, fully offline)

Nothing to set up beyond what's already in the repo:

```bash
docker compose up -d      # starts local Postgres (with PostGIS) + Mongo + Redis
mvn spring-boot:run -pl bmp-user   # or any other service — uses application.yml's local defaults
```

No environment variables needed — `application.yml`'s defaults already point here.

---

## Step 4 — Staging / Production (later)

Same mechanism, different values: a **separate** Neon project (not a branch of dev — a fully
separate project) and a **separate** Atlas cluster (likely a paid tier once there's real user
data to protect — free M0 has no backups). Secrets get set via your deployment platform's
environment variable / secrets manager, never committed to `application.yml` or to git.

---

## Rules

- **Never commit real credentials.** The defaults in `application.yml` (`devonly` password,
  `localhost`) are for local Docker only and are fine to keep in git. Neon/Atlas/staging/prod
  credentials are set as environment variables on your own machine (or a `.env` file that's
  gitignored — add one if it doesn't exist yet) and never pasted into any `.yml` file.
- **Keep `BMP_DB_POOL_SIZE` low on shared instances.** If you're running the whole 9-service
  stack at once against your Neon branch, do the math before raising it.
- **Coordinate before pointing at the shared `main` Neon branch or shared Mongo database** —
  it's meant for occasional integration checks, not as your default day-to-day target.
