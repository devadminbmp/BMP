# config-repo

Session 7. Source of truth for every BMP service's *hot-reloadable* runtime properties —
things you'd want to change without a redeploy (feature flags, log levels, timeouts,
tunable thresholds). Served by `bmp-config-server` (port 8888) directly from this
directory in this same GitHub repo.

## What does NOT belong here

Secrets: database passwords, `BMP_JWT_SECRET`, `BMP_INTERNAL_SERVICE_KEY`, SMTP/SMS
credentials, the config-server's own basic-auth password. All of those stay as
environment variables set outside git, per `docs/project-management/DATABASE_SETUP.md`'s
existing rule — this repo (even a private one) is not where secrets live.

## File naming

Spring Cloud Config matches files to the requesting service's `spring.application.name`:

| File | Applies to |
|---|---|
| `application.yml` | every service (layered UNDER the per-service file below) |
| `bmp-auth-service.yml` | bmp-auth |
| `bmp-user-service.yml` | bmp-user |
| `bmp-salon-service.yml` | bmp-salon |
| `bmp-booking-service.yml` | bmp-booking |
| `bmp-payment-service.yml` | bmp-payment |
| `bmp-review-service.yml` | bmp-review |
| `bmp-rewards-service.yml` | bmp-rewards |
| `bmp-admin-service.yml` | bmp-admin |
| `bmp-notification-service.yml` | bmp-notification |
| `api-gateway.yml` | api-gateway |

Only `bmp-auth-service.yml`, `bmp-payment-service.yml`, and `bmp-notification-service.yml`
exist so far, as worked examples — add the rest as real hot-reloadable properties come up
for those services. A missing file for a given service is fine; that service just gets
nothing from here beyond `application.yml`'s shared defaults.

## How a change actually takes effect

1. Edit the relevant file here, commit, push to GitHub.
2. Either:
   - Wait for the GitHub webhook (once wired — see `bmp-config-server`'s own
     `application.yml`, `spring.cloud.config.monitor.github.secret`) to fire
     automatically, or
   - `POST /actuator/busrefresh` to any ONE running BMP service manually. Spring Cloud
     Bus (Kafka transport) relays the refresh to every other service.
3. Only beans annotated `@RefreshScope` actually pick up new values without a full
   restart — see `AuthService` and `PaymentOrderService` for the pattern. A `@Value` on a
   bean that ISN'T `@RefreshScope` will silently keep its old value until the next
   restart; add the annotation when you add a new hot-reloadable property.

## Before any of this works

- `bmp-config-server` needs `BMP_CONFIG_GIT_URI` pointed at the real repo (defaults to
  `https://github.com/devadminbmp/BMP.git`) and `BMP_CONFIG_GIT_BRANCH` confirmed against
  this repo's actual default branch — not verified against the live GitHub repo as part of
  this pass.
- If `devadminbmp/BMP` is private, `BMP_CONFIG_GIT_USERNAME` / `BMP_CONFIG_GIT_PASSWORD`
  (a GitHub Personal Access Token) need to be set.
- The GitHub webhook itself (Settings → Webhooks) still needs to be added by hand in the
  GitHub UI — not something settable from a file in the repo.
