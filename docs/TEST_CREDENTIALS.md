# Test credentials — every role

Who to sign in as, in each of the three apps, and what actually works today.

> **Nothing in this file is a production credential.** The seeded phone numbers are reserved
> ranges, the console demo accounts only exist when mocks are on, and the one real account
> (`devadmin.bmp@gmail.com`) ships **with no password at all** — see §3.
>
> **The real Gmail app password and any live secrets belong in `local-secrets.ps1`, which is
> gitignored. Never put them in this file.**

---

## The honest state of things first

| App | Can you sign in right now? |
|---|---|
| Console (BMP-ADMIN) | **Yes** — mock mode, no backend needed. All five roles. |
| Customer app (BMP-FE) | **Only with the backend running.** Browsing works without it; login does not. |
| Backend (BMP) | Never compiled in this environment — no JDK or Maven here. Run `mvn -q verify` yourself first. |

**Why login isn't mocked in the customer app.** Every other API in BMP-FE has a mock behind
`EXPO_PUBLIC_USE_MOCKS`, and that flag **defaults to `true`**. If auth had a mock too, any build
where someone forgot to set the flag to `false` would ship an authentication bypass. A default
that fails open on auth is not worth the convenience, so `../../BMP-FE/src/api/auth.ts` always talks to
`bmp-auth`. Browse, search and view salons offline; the moment you tap **Book**, you need the
backend.

---

## 1. Customer app (BMP-FE) — customers, owners, managers, stylists

Requires: Postgres + `bmp-auth` + `bmp-user` (+ gateway + Eureka) running, and the seed applied.

```bash
docker compose up -d
# let the services boot once so Flyway creates the schemas, then:
docker exec -i bmp-postgres-1 psql -U bmp -d bmp < seed/dev-seed.sql
```

**The OTP is `000000` for every one of these accounts.**
`bmp-auth`'s `application.yml` sets `dev-master-otp: ${BMP_DEV_MASTER_OTP:000000}`. The `@Value`
in `AuthService` defaults to **blank (disabled)** — so the master OTP exists *only* because the
YAML supplies it, and it disappears the moment you deploy with `BMP_DEV_MASTER_OTP=` set empty.
Do that for anything internet-facing. Real codes still arrive by email regardless.

### Seeded accounts (`../seed/dev-seed.sql`)

| Role | Phone (this is the login) | Name | Email on the record |
|---|---|---|---|
| Customer | `+919876500001` | Priya Sharma | priya.customer@example.com |
| Customer | `+919876500002` | Arjun Mehta | arjun.customer@example.com |
| **Salon owner** | `+919876500003` | Kavya Reddy | owner.lumiere@example.com |
| **Manager** | `+919876500004` | Rahul Nair | manager.lumiere@example.com |
| **Stylist** | `+919876500005` | Ravi Kumar | ravi.stylist@example.com |
| **Salon owner** (2nd salon) | `+919876500006` | Sneha Iyer | owner.aura@example.com |

All six are `is_verified = true`, so they log in rather than signing up. Kavya and Rahul are on
**Lumière Salon & Spa** (`00000000-0000-7000-0001-000000000001`); Sneha owns the second salon.
The seed also carries 8 salons, 55 services and 7 stylists — enough for the desk and the booking
flow to look real.

**Sign in:** enter the phone → `000000`. Owner and manager land on their salon; the manager desk
is the same screen with the owner-only controls absent.

### Creating fresh accounts instead

Any unseen phone number signs up on `/otp/verify`, with `role` deciding what you become:

| `role` | Extra fields | Notes |
|---|---|---|
| `customer` | — | The default if you send nothing. |
| `salon_owner` | salon details | Or use the 4-step signup sheet in the app. |
| `manager` | **`inviteToken` required** | You cannot self-declare as a manager — get the token from an owner's Team panel. |
| `stylist` | `name`, `inviteToken` | Invited from the manager desk. |

That the manager path *needs* an invite is the point: role is granted by someone who already
owns the salon, never claimed by the person signing up.

### Mock mode (no backend) — what you get

`EXPO_PUBLIC_USE_MOCKS` defaults to `true`. Salons, services, availability, the manager desk,
bookings and coupons all return seeded mock data that **actually mutates** — approving, blocking
and cancelling visibly change state. Login is the only thing that won't work.

---

## 2. Console (BMP-ADMIN) — support, ops, finance, read-only, superadmin

```bash
cd BMP-ADMIN
npm install
npm run dev      # http://localhost:5180
```

Mock mode is the default (`VITE_USE_MOCKS` is only false if you set it). **The login screen now
shows a row of role buttons** — click one to fill the form. That block renders only when
`USE_MOCKS` is true, so it cannot appear in a real deployment.

| Door | Email | Password | 2FA code |
|---|---|---|---|
| `/admin/login` | `super@bemyprofessional.in` | anything ≥4 chars | any 6 digits |
| `/admin/login` | `priya@bemyprofessional.in` | anything ≥4 chars | any 6 digits |
| `/support/login` | `arjun@bemyprofessional.in` | anything ≥4 chars | any 6 digits |
| `/support/login` | `finance@bemyprofessional.in` | anything ≥4 chars | any 6 digits |
| `/support/login` | `readonly@bemyprofessional.in` | anything ≥4 chars | any 6 digits |

An unrecognised email signs you in as the ops admin, so a typo doesn't dead-end a demo.

### What each role is for — and what to check

| Role | Sees | The thing worth verifying |
|---|---|---|
| **Superadmin** | Everything | **Staff accounts** is the only screen this role has that ops doesn't. Create an employee, get a `BMP-XXXX-XXXX` code. |
| **Ops** | Approvals, data requests, audit, settings, all support tools | Can flip the kill switch; **cannot** create staff. |
| **Support** | Tickets, customer help, bookings, salon help, refunds, coupons | **Cannot** reach `/admin/*` at all — the admin door refuses the role by name and links to the support console. Cannot approve salons or change settings. |
| **Finance** | Bookings, refunds, audit | The only role besides superadmin with `refund:issue`. **No** customer contact details. |
| **Read only** | Salons, users, bookings, tickets — viewing only | Deliberately **lacks `user:pii_reveal`**: read-only means read the platform, not read phone numbers. |

Two things are genuinely worth clicking through, because they're the ones that are easy to get
wrong and hard to notice:

1. Sign in as **support** at `/admin/login`. Both factors pass, then you're told plainly it's the
   wrong console, with a link. Not a silent redirect, not a bare 403.
2. Sign in as **read only** and open a customer. The phone stays masked and the reveal button is
   gone — the server would refuse anyway, but the UI shouldn't offer it.

### Coupons, by role

Both support and admin reach **Coupons**, and get different forms:

- **Admin / ops:** all audiences — all users, selected users, all salons, selected salons, new
  users, referred users.
- **Support:** **selected users only**, and the form asks for the ticket the goodwill relates to.
  A support agent should not be able to discount the entire platform from a chat window.

The server decides this, not the nav.

---

## 3. Console against a real backend — the superadmin

**There is no default password, on purpose.**

`V003__admin_console_hardening.sql` seeds one account:

```
id     01930000-0000-7000-8000-000000000001
email  devadmin.bmp@gmail.com
phone  +910000000001
role   super_admin
password_hash  'LOCKED-NO-PASSWORD-SET'
```

That last value is **not a bcrypt hash**, so verification can only ever fail. A password written
into a migration lives in git forever and gets copied into staging; a documented default gets
guessed. Claim the account once, from your own machine:

```bash
export BMP_ADMIN_BOOTSTRAP_EMAIL=devadmin.bmp@gmail.com
export BMP_ADMIN_BOOTSTRAP_PASSWORD='<16+ characters, from a password manager>'
# start bmp-admin — then REMOVE both variables
```

`StaffBootstrap` applies the password **only if the account still has no usable one**, so the
variables are inert on every later boot and cannot reset a live account. Two-factor is not
pre-enrolled: the first sign-in forces enrolment before anything else is reachable.

> **If you ran an early build of V003:** it briefly seeded a bcrypt hash copied from Spring
> Security's own documentation — a publicly known value. If your database has it, run
> `UPDATE admin_schema.bmp_staff SET password_hash = 'LOCKED-NO-PASSWORD-SET' WHERE id = '01930000-0000-7000-8000-000000000001';`
> and bootstrap again.

### All five console roles at once — local only

The manual path below is correct and stays correct, but it's five minutes of clicking *per
role*, repeated after every `docker compose down -v`. The predictable result is that nobody ever
tests as a support agent or a read-only analyst, and the least-privilege rules this console's
design rests on go unverified until a real employee hits them.

So there's a seeder. **It only runs against a database on localhost**, and it's off by default:

```powershell
$env:BMP_ADMIN_DEV_STAFF = "true"
mvn -pl bmp-admin spring-boot:run
```

At startup it prints, once:

```
DEV STAFF ACCOUNTS SEEDED (5 created) — LOCAL DATABASE ONLY
Password for ALL of the accounts below:
    <20 random characters>

  dev.super@bemyprofessional.in     super_admin      (admin door + Staff accounts)
  dev.ops@bemyprofessional.in       ops_admin        (admin door, no Staff accounts)
  dev.support@bemyprofessional.in   support_agent    (support door only)
  dev.finance@bemyprofessional.in   finance_admin    (support door, refund approver)
  dev.readonly@bemyprofessional.in  read_only        (support door, NO PII reveal)

Two-factor: add this ONE entry to your authenticator — it works for all five.
    otpauth://totp/...
```

All five share one TOTP secret, so a single authenticator entry covers every role — otherwise
people go back to using the superadmin for everything, which is the behaviour this is trying to
prevent. Set `BMP_ADMIN_DEV_STAFF_PASSWORD` (16+ chars) if you'd rather choose the password than
copy it off the console.

**Three guards, and the second is the one that matters:**

1. `bmp.admin.dev-staff.enabled` defaults to **false**.
2. **The datasource must be on localhost.** That's a JDBC-URL check, not a `@Profile`
   annotation, because in this repo **the `dev` profile points at a shared Neon branch** — a
   seeder gated on `@Profile("dev")` would have written known-password admin accounts into a
   database three founders share. Profile names lie; a JDBC URL doesn't.
3. Existing accounts are never modified, so a local database mid-test keeps its state.

The password is random per run and printed to the console only — never in a migration, never in
a file, never in git. That restraint isn't theoretical: V003 once shipped a bcrypt hash copied
from Spring Security's own documentation.

Never set `BMP_ADMIN_DEV_STAFF` in a deployed environment. Guard 2 means it wouldn't do
anything, but it shouldn't be sitting there to be misread either.

### Everyone else — the real onboarding path

The superadmin creates them: **Staff accounts → Add employee**. There's no password field on that
form. You get a one-time `BMP-XXXX-XXXX` code (single-use, 48 hours), or an activation link
`/activate?code=…`, which the new joiner redeems to set a password **nobody else ever sees**,
followed by forced 2FA enrolment.

An admin who knows a colleague's password makes every action that colleague takes deniable —
"someone else could have logged in as me" becomes true — which destroys the audit log's value as
evidence exactly when you need it. Hence no reset either: a locked-out person gets a fresh code,
which also clears their 2FA in case they lost their phone.

---

## 4. Where the real secrets live

| Secret | Where |
|---|---|
| Gmail app password for OTP delivery (`devadmin.bmp@gmail.com`) | `local-secrets.ps1` — **gitignored** |
| Superadmin console password | Your password manager. Set once via bootstrap, never written down here. |
| `BMP_DEV_MASTER_OTP` | Set to **empty** for anything reachable from the internet. |
| Google OAuth web client ID | Public by design; the secret is not. |

Before any deploy that isn't your laptop: `BMP_DEV_MASTER_OTP=`, `VITE_USE_MOCKS=false`,
`EXPO_PUBLIC_USE_MOCKS=false`. Those three flags are the entire difference between a demo build
and one that checks who you are.
