# BMP — credentials, one page

Everything you need to sign in as any role, in any of the three apps.
Verified against the code on 25 Aug 2026 (Session 43).

> **No production secrets live here.** The seeded phone numbers are reserved ranges; the console
> demo accounts only exist in mock mode; the one real console account ships with **no password**.
> Real secrets belong in `local-secrets.ps1`, which is **gitignored**. Never paste one into a
> file that git tracks.

For the reasoning behind any of this — why there's no default password, why `000000` is
restricted, why login isn't mocked — see [`docs/TEST_CREDENTIALS.md`](docs/TEST_CREDENTIALS.md).
This page is the lookup; that one is the argument.

---

## 1. Customer app / salon desk (BMP-FE)

### Setup — required before any login works

```powershell
cd "C:\BMP -PRJ\BMP"
docker compose up -d

# Start the services ONCE so Flyway creates the schemas, then load the seed.
# NOT `psql < file` — `<` is a reserved operator in PowerShell and fails before Docker runs.
docker cp seed\dev-seed.sql bmp-postgres-1:/tmp/dev-seed.sql
docker exec bmp-postgres-1 psql -U bmp -d bmp -f /tmp/dev-seed.sql

# Confirm. All four numbers matter — `staff` is the one people forget.
docker exec bmp-postgres-1 psql -U bmp -d bmp -c "
SELECT (SELECT count(*) FROM user_schema.users)          AS users,     -- >= 6
       (SELECT count(*) FROM salon_schema.salon)         AS salons,    -- 8
       (SELECT count(*) FROM salon_schema.salon_service) AS services,  -- 55
       (SELECT count(*) FROM salon_schema.salon_staff)   AS staff;"    -- 3
```

**If `users` is 0, every login fails with *"No account found for this number"*.**
**If `staff` is 0, the owner signs in fine and then gets 403 on every panel** — their JWT carries
`salonId = null`, because `resolveSalonScope()` reads `salon_staff` on each token mint.

### Services, and the order to start them

| Order | Service | Port | Needed for login? |
|---:|---|---:|---|
| 1 | `eureka-server` | 8761 | **Yes** — nothing resolves without it |
| 2 | `bmp-user` | 8082 | **Yes** — decides login vs signup |
| 3 | `bmp-salon` | 8083 | **Yes for owner/manager** — resolves the salon seat |
| 4 | `bmp-auth` | 8081 | **Yes** |
| 5 | `bmp-notification` | 8089 | Only if you need a real emailed code |
| 6 | `api-gateway` | 8080 | **Yes** — this is what the browser talks to |

```powershell
java -jar eureka-server\target\eureka-server-0.1.0-SNAPSHOT.jar
# ...one terminal each, then wait ~30s — Eureka registration isn't instant.
```

The frontend's `EXPO_PUBLIC_API_URL` is `http://localhost:8080`, so **the gateway must be up**.
Without it you get axios *"Network Error"* — no status code, because nothing answered.

### The six seeded accounts

**OTP for all six: `000000`.**

| Role | Phone — this is the login | Name | Email on record |
|---|---|---|---|
| Customer | `9876500001` | Priya Sharma | priya.customer@example.com |
| Customer | `9876500002` | Arjun Mehta | arjun.customer@example.com |
| **Salon owner** | `9876500003` | Kavya Reddy | owner.lumiere@example.com |
| **Manager** | `9876500004` | Rahul Nair | manager.lumiere@example.com |
| **Stylist** | `9876500005` | Ravi Kumar | ravi.stylist@example.com |
| **Salon owner** (2nd salon) | `9876500006` | Sneha Iyer | owner.aura@example.com |

**Type 10 digits only.** The field renders a fixed `+91`; typing `919876500003` used to build
`+91919876500003` and match nobody. It's rejected now, but the habit is the trap.

Kavya and Rahul are both on **Lumière Salon & Spa**; Sneha owns **Aura Beauty Lounge**. The seed
also carries 8 salons, 55 services and 7 stylists, so the desk and the booking flow look real.

### Three rules that will bite you

| Rule | What you'll see |
|---|---|
| **`000000` works for these six numbers ONLY** | Any other number gets a real emailed code. That's deliberate — it's how you test signup. |
| **Codes are single-use** (V005) | Re-entering one gives `410 — This code has already been used`. Press **Resend code**. |
| **Codes expire in 5 minutes** | `410 — OTP expired`. Press **Resend code**. |

Five wrong attempts locks the phone for **15 minutes**, and `000000` will *not* rescue you — the
lockout check runs before it. To clear it:

```powershell
docker exec bmp-postgres-1 psql -U bmp -d bmp -c "DELETE FROM user_schema.otp_requests WHERE phone LIKE '%9876500003';"
```

### Creating fresh accounts

Any unseen number signs up at `/otp/verify`; `role` decides what you become.

| `role` | Extra fields | Note |
|---|---|---|
| `customer` | — | The default if you send nothing |
| `salon_owner` | salon details | Or use the 4-step signup sheet in the app |
| `manager` | **`inviteToken` required** | You cannot self-declare as a manager |
| `stylist` | `name`, optional `inviteToken` | Invited from the manager desk |

That the manager path *needs* an invite is the point: the role is granted by someone who already
owns the salon, never claimed by the person signing up.

### Where the code actually arrives

**Email — the only live channel.** Set on `bmp-notification`:

```powershell
$env:BMP_EMAIL_PROVIDER = "smtp"
$env:BMP_SMTP_USERNAME  = "devadmin.bmp@gmail.com"
$env:BMP_SMTP_PASSWORD  = "<the app password from local-secrets.ps1>"
```

Leave `BMP_EMAIL_PROVIDER` unset (`log`) and the code prints to the bmp-notification console
instead. **SMS and WhatsApp send nothing** — they're stubs, off by default, blocked on TRAI DLT
registration and a WhatsApp Business account respectively. Neither is blocked on code.

### Mock mode — no backend at all

`EXPO_PUBLIC_USE_MOCKS` defaults to `true`. Salons, services, availability, the desk, bookings and
coupons all return mock data that genuinely mutates. **Login is the one thing that won't work**,
and that's on purpose: if auth had a mock, any build where someone forgot the flag would ship an
authentication bypass.

---

## 2. Admin console (BMP-ADMIN) — mock mode

```powershell
cd "C:\BMP -PRJ\BMP-ADMIN"
npm install
npm run dev          # http://localhost:5180
```

Mock mode is the default. The login screen shows role buttons — click one to fill the form. That
block renders only when `USE_MOCKS` is true, so it can't appear in a real deployment.

| Door | Email | Password | 2FA |
|---|---|---|---|
| `/admin/login` | `super@bemyprofessional.in` | any ≥4 chars | any 6 digits |
| `/admin/login` | `priya@bemyprofessional.in` | any ≥4 chars | any 6 digits |
| `/support/login` | `arjun@bemyprofessional.in` | any ≥4 chars | any 6 digits |
| `/support/login` | `finance@bemyprofessional.in` | any ≥4 chars | any 6 digits |
| `/support/login` | `readonly@bemyprofessional.in` | any ≥4 chars | any 6 digits |

An unrecognised email signs you in as ops admin, so a typo doesn't dead-end a demo.

| Role | Can do | Notably cannot |
|---|---|---|
| **Superadmin** (`super@`) | Everything, incl. Staff accounts | — |
| **Ops** (`priya@`) | Approvals, data requests, audit, settings, kill switch | Create staff |
| **Support** (`arjun@`) | Tickets, bookings, salon help, refunds, coupons | Reach `/admin/*` at all |
| **Finance** (`finance@`) | Bookings, refunds, audit; `refund:issue` | See customer contact details |
| **Read only** (`readonly@`) | View salons, users, bookings, tickets | Reveal PII — deliberately |

---

## 3. Admin console against a real backend

**There is no default password.** `V003` seeds `devadmin.bmp@gmail.com` with
`password_hash = 'LOCKED-NO-PASSWORD-SET'` — not a bcrypt hash, so verification can only fail. A
password in a migration lives in git forever; a documented default gets guessed.

Claim it once, from your own machine:

```powershell
$env:BMP_ADMIN_BOOTSTRAP_EMAIL    = "devadmin.bmp@gmail.com"
$env:BMP_ADMIN_BOOTSTRAP_PASSWORD = "<16+ chars, from your password manager>"
mvn -pl bmp-admin spring-boot:run
# then REMOVE both variables
```

It applies only if the account still has no usable password, so it's inert on later boots and
cannot reset a live account. 2FA isn't pre-enrolled — first sign-in forces enrolment.

### All five console roles at once — localhost only

```powershell
$env:BMP_ADMIN_DEV_STAFF = "true"
mvn -pl bmp-admin spring-boot:run
```

Prints once, at startup: five accounts (`dev.super@`, `dev.ops@`, `dev.support@`, `dev.finance@`,
`dev.readonly@` — all `@bemyprofessional.in`), one shared random password, and **one TOTP URI that
works for all five**. Set `BMP_ADMIN_DEV_STAFF_PASSWORD` (16+ chars) to choose it yourself.

Three guards, and the second is the one that matters: the property defaults to false; **the
datasource must be on localhost** (a JDBC-URL check, not `@Profile` — in this repo the `dev`
profile points at a shared Neon branch, so a profile-gated seeder would have written
known-password admins into a database three founders share); and existing accounts are never
modified. **Profile names lie; a JDBC URL doesn't.**

---

## 4. Where the real secrets live

| Secret | Where |
|---|---|
| Gmail app password for OTP delivery | `local-secrets.ps1` — **gitignored** |
| Superadmin console password | Your password manager. Set once via bootstrap. |
| `BMP_DEV_MASTER_OTP` | Set **empty** for anything reachable from the internet |
| Google OAuth web client ID | Public by design; the secret is not |

**Before any deploy that isn't your laptop:**

```
BMP_DEV_MASTER_OTP=          # disables 000000 entirely
VITE_USE_MOCKS=false         # console talks to the real backend
EXPO_PUBLIC_USE_MOCKS=false  # app talks to the real backend
```

Those three flags are the entire difference between a demo build and one that checks who you are.

---

## Troubleshooting, by what you actually see

| Message | Cause | Fix |
|---|---|---|
| `No account found for this number` | Seed not loaded | §1 setup |
| Owner logs in, then 403 everywhere | `salon_staff` empty | Re-run the seed; check `staff` = 3 |
| `Network Error` (no status) | Gateway (8080) down | Start `api-gateway` |
| `503 — Could not verify the account` | `bmp-user` unreachable | Start it; check Eureka at :8761 |
| `UnknownHostException: bmp-user-service` | Not registered in Eureka | Start `bmp-user`, wait 30s |
| `410 — already been used` | Codes are single-use | Press **Resend code** |
| `410 — OTP expired` | 5-minute TTL | Press **Resend code** |
| `423` / `Too many incorrect attempts` | 5 failures = 15-min lock | Delete the `otp_requests` rows (§1) |
| `email is required to sign up` | Pre-Session-43 build | Rebuild — the message is wrong, the cause is "no account" |
| `NoClassDefFoundError: com/bmp/common/…` | JVM older than your last build | `mvn -DskipTests install`, then **restart** |

## MinIO — object storage (Session 44)

Image uploads for salon galleries, service photos and cover images.

| What | Value |
|---|---|
| Console (browse the bucket) | http://localhost:9001 |
| S3 API (what bmp-salon uses) | http://localhost:9000 |
| Username | `bmp-dev` |
| Password | `devonly-minio-password` |
| Bucket | `bmp-media` (created automatically on bmp-salon startup) |

Start it with the rest of the infrastructure: `docker compose up -d`.

**If uploads fail**, check bmp-salon's startup log. It says plainly whether object storage was
reachable, and it does NOT stop the service from starting — a storage outage must not take down
booking and the salon desk over a feature used a few times a week. So the desk working is not
evidence that uploads work; the log line is.

**`docker compose down -v` deletes uploaded photos.** The `miniodata` volume is named, so an
ordinary `down` is safe — but unlike the database there is no seed script to rebuild this, and
`-v` is the documented fix for a poisoned Postgres volume. Worth knowing before you type it.

Moving to real S3 / Cloudflare R2 later is the six `bmp.storage.*` values in
`bmp-salon/src/main/resources/application.yml` — all six read from environment variables, and the
file carries an R2 example. No code changes.

## Not receiving email? Read this first (Session 47)

### The one-line diagnosis

Look at bmp-notification's log when an OTP is requested:

| What you see | Meaning |
|---|---|
| `LoggingEmailSender : [EMAIL STUB — no SMTP provider configured]` | **The credentials never reached the JVM.** Everything else works — the event arrived, the address is right. It is being printed instead of sent. |
| `Email sent to=... subject="..."` | Delivered to Gmail. If it's still not in the inbox, check spam. |
| `NOTIFICATION SEND FAILED [...]` | SMTP rejected it. The logged root cause names the fix. |

### The fastest fix — a file, not a shell (recommended)

`local-secrets.ps1` sets PowerShell `$env:` variables, which only reach processes started from
**that same shell**. **IntelliJ run configurations do not inherit them.** If you start services from
the IDE — a compound run config, say — the script cannot reach them however carefully it was
sourced in a terminal, and the service silently falls back to `LoggingEmailSender`.

So Spring now also reads a plain properties file, whatever launched the JVM:

```
copy local-secrets.example.properties local-secrets.properties
```

then fill in four values and restart bmp-notification:

```properties
bmp.smtp.provider=smtp
bmp.smtp.username=devadmin.bmp@gmail.com
bmp.smtp.password=<16-char app password>
bmp.smtp.from=devadmin.bmp@gmail.com
```

`local-secrets.properties` is gitignored. Env vars still take precedence, so the `.ps1` route keeps
working for anyone already using it.

**Confirm it worked** — this appears at startup, before any OTP:

```
SMTP OK — connected and authenticated to smtp.gmail.com:587 as devadmin.bmp@gmail.com
```

If instead you see `EMAIL IS IN LOG-ONLY MODE`, the file isn't being found: it must sit next to
`pom.xml` in the repo root, and Spring looks in both the working directory and its parent.


Two causes, in order of likelihood. **The bmp-notification startup log tells you which.**

### 1. The secrets weren't in the shell that started bmp-notification

`local-secrets.ps1` sets `BMP_EMAIL_PROVIDER=smtp`. But PowerShell's `$env:X = "..."` only affects
the CURRENT shell and its children — dot-source it in one terminal, start the service in another,
and the service sees none of it.

When that happens **nothing fails**. `bmp.notification.email-provider` falls back to its default of
`log`, `LoggingEmailSender` wins the `@ConditionalOnProperty`, and every email is printed to the
console instead of sent. Signup looks like it worked; the OTP never arrives.

**The decisive check** — look at bmp-notification's startup log:

| Line at startup | Meaning |
|---|---|
| `EMAIL IS IN LOG-ONLY MODE — no real emails will be sent.` | This is your problem. |
| `SmtpEmailSender active — real email delivery enabled (from=...)` | Email is live; the problem is elsewhere. |

**The fix**, from `C:\BMP -PRJ\BMP`:

```powershell
.\run-service.ps1 bmp-notification
```

`run-service.ps1` dot-sources the secrets and starts the service in the same process, so the two
cannot drift apart. It prints EMAIL: REAL DELIVERY or EMAIL: LOG ONLY before starting.

### 2. The outbox → Kafka relay was off (fixed in Session 47)

Even with SMTP on, an email only sends if its event reaches bmp-notification. `OutboxKafkaRelay`
is `@ConditionalOnProperty(havingValue = "true")` with **no** `matchIfMissing`, so a service that
omits the key doesn't disable the relay loudly — the bean is simply never created and nothing says
so. The rows commit to the outbox table and sit there forever.

`bmp-auth` had the key since Session 16. **`bmp-salon`, `bmp-booking` and `bmp-rewards` never did**,
which is why OTP email worked and nothing else ever did:

| Service | Events that were stranded |
|---|---|
| bmp-salon | `salon.status.changed` — owner never told they were approved or rejected |
| bmp-booking | `booking.created/cancelled/rescheduled/completed` — no booking mail, to anyone |
| bmp-rewards | `coupon_request.raised/decided` — offer decisions never delivered |

Both halves are now in place on all three: `bmp.outbox.relay.enabled` **and** `@EnableScheduling`
(the relay is a `@Scheduled` poll — with the property set but scheduling off, the bean exists and
its method never runs, which looks identical from outside).

**Also required:** Kafka must be up (`docker compose up -d`). The relay polls the outbox and
publishes; with no broker the events stay put.

### 3. The send fails and the log doesn't say why (fixed in Session 47)

If bmp-notification RECEIVES the event but no mail arrives, the send itself is failing. Until
Session 47 you could not tell why, because `dispatch()` logged `e.getMessage()` as a **string**
rather than passing the throwable — so SLF4J printed one line and discarded the cause chain. For
SMTP that chain IS the diagnosis:

| Root cause in the log | What it means | Fix |
|---|---|---|
| `AuthenticationFailedException: 535-5.7.8 Username and Password not accepted` | app password wrong or revoked | new one at myaccount.google.com > App passwords |
| `SMTPSendFailedException: 553 5.7.1 ... not owned by user` | `BMP_EMAIL_FROM` isn't the authenticated account | make it equal `BMP_SMTP_USERNAME` |
| `ConnectException` / `SocketTimeoutException` | nothing reached Gmail | firewall/network blocking outbound 587 |
| `MailAuthenticationException` with an empty username | secrets not loaded in this shell | see cause 1 above |

Now the throwable is logged in full, and **`SmtpEmailSender` tests the connection at startup**, so
a bad password appears the moment the service boots rather than on the first signup:

```
SMTP OK — connected and authenticated to smtp.gmail.com:587 as devadmin.bmp@gmail.com
```
```
SMTP CONNECTION FAILED to smtp.gmail.com:587 ... NO EMAIL WILL BE DELIVERED. Cause: ...
```

Not fatal on purpose: bmp-notification also serves SMS/WhatsApp stubs and the notification log, and
refusing to boot over email would take those down too.

### 4. Two silent skips worth knowing about

**An OTP for a user with no email on file is never sent.** `AuthService` resolves the address as
"the existing user's email, else the one in the request". Signup requires an email, so accounts
created that way are fine — but a user whose `users.email` is NULL (seeded rows, older accounts)
logging in sends no email in the request, so the address resolves to null and the email channel is
skipped. The request still returns 200 and the code is still generated. It logs loudly; check for
that warning if one specific account never receives anything.

**Notifications with no recipient user id used to be dropped entirely.** `notification_log.recipient_user_id`
is NOT NULL, so `dispatch()` threw on the log INSERT *before* attempting the send — the message was
never sent, and the only trace was a database constraint error that reads like a logging problem.
Affected `salon.status.changed` when the owner lookup failed. Now a placeholder id is used and the
send always happens: bookkeeping failing must never cancel the thing being booked.

### Checking it end to end

```sql
-- Anything stuck? Should trend to empty while the relay runs.
SELECT event_type, count(*) FROM common_schema.outbox
WHERE published_at IS NULL GROUP BY event_type;
```

Then request an OTP and watch bmp-notification's console for `Email sent to=... subject="..."`.
If you see `Email send FAILED`, the message names the SMTP reason — usually a Gmail app password
that has been revoked, or `BMP_EMAIL_FROM` not matching `BMP_SMTP_USERNAME` (Gmail refuses to send
as an address you haven't authenticated as).
