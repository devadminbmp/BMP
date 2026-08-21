# Admin console API — bmp-admin

The internal staff console's backend. **Separate identity, separate tokens, separate service**
from everything customer-facing.

Base path: `/api/v1/admin` · Port `8088` · Swagger: `http://localhost:8088/swagger-ui/index.html`

---

## 1. Why staff are not users

There is no row in `user_schema.users` for a support agent, and no role on a customer account
grants console access.

| | Customer (bmp-auth) | Staff (bmp-admin) |
|---|---|---|
| Table | `user_schema.users` | `admin_schema.bmp_staff` |
| Login | Phone + OTP | Password **+ TOTP** |
| Signing key | `bmp.auth.jwt-secret` | `bmp.admin.jwt-secret` — **different** |
| Audience claim | (customer) | `bmp-admin` |
| Session | 30 days, rotating | 7 days, revocable server-side |
| Access token | 15 min | 15 min |

An internal console can read every customer's phone number and every salon's takings. If staff
identity lived in the customer table, one privilege-escalation bug anywhere in the customer app
would be a total compromise. Two identity systems means an attacker must break both.

`AdminSecurityConfig` registers its own filter chain at `@Order(1)` matching
`/api/v1/admin/**`, so admin routes never touch bmp-common's customer JWT filter — and the
internal service key that lets services call each other grants nothing here.

---

## 2. Getting the master admin working (once)

`V003` seeds a superadmin with **no usable password** — `password_hash = 'LOCKED-NO-PASSWORD-SET'`,
which is not a bcrypt hash, so verification can only fail. There is no default password to leak
or forget to change.

```bash
export BMP_ADMIN_BOOTSTRAP_EMAIL=you@bemyprofessional.in
export BMP_ADMIN_BOOTSTRAP_PASSWORD='<16+ characters, from a password manager>'
# start bmp-admin, watch for: "BOOTSTRAP: password set for staff account ..."
# then REMOVE both variables
```

`StaffBootstrap` acts **only if the account still has no usable password**, so the variables are
inert on every later boot and cannot reset a live account. Passwords under 16 characters are
refused outright.

Your first login then forces TOTP enrolment before anything else is reachable.

---

## 3. Login is two calls

```
POST /auth/login          { email, password }
  → 200 { stage: "TOTP_REQUIRED",            challengeToken }
  → 200 { stage: "TOTP_ENROLMENT_REQUIRED",  challengeToken, totpProvisioningUri }
  → 401 "Email or password is incorrect."

POST /auth/totp/verify    { challengeToken, code }
POST /auth/totp/enrol     { challengeToken, code }     ← first login only
  → 200 { stage: "AUTHENTICATED", accessToken, refreshToken, expiresIn, staff }
```

**Every failure returns the same 401 with the same wording** — unknown email, wrong password,
suspended, never activated. Distinguishing them would let anyone discover which emails are
staff. The service also hashes a dummy value when no account exists, so timing doesn't leak it
either.

**The challenge token is not a session token.** It carries `stage=totp_challenge` and lives 3
minutes. `StaffAuthFilter` only authenticates `stage=session`. Without that separation "passed
the password" would equal "logged in", which makes the second factor decorative.

**Enrolment persists the secret only after a valid code proves the app has it.** Abandoning
setup leaves the account exactly as it was, rather than locked out of a factor it thinks it has.

Other endpoints: `POST /auth/refresh`, `GET /auth/me`, `POST /auth/logout`,
`POST /auth/activate`.

---

## 4. The master admin creates employees

```
POST   /staff                    { name, email, phone, role }   → 201 + ONE-TIME activation code
GET    /staff                                                    → all staff, newest first
PUT    /staff/{id}/status        { status, reason }
POST   /staff/{id}/reissue                                       → new one-time code
```

Superadmin only — enforced by `@PreAuthorize` **and** re-checked in `StaffAdminService`.
Creating accounts is the power that grants every other power; an ops admin who could create
accounts could create a superadmin.

### There is no password field, deliberately

The admin creates the **account** and receives a code like `BMP-4K7P-9X2M`. The employee
redeems it and sets a password nobody else has ever seen.

An admin who knows a colleague's password makes every action that colleague takes deniable —
"someone else could have logged in as me" becomes true — which destroys the audit log's value
as evidence exactly when you need it. It also means the password travels over WhatsApp, and
never gets changed.

The code is shown **once** (only its hash is stored), works once, and expires in 48 hours.
Characters that get confused when read aloud are excluded: no `0`/`O`, no `1`/`I`/`l`.

The employee's flow:

```
POST /auth/activate   { activationCode, newPassword }   ← min 16 characters
→ account becomes 'active'; next login walks them through TOTP enrolment
```

### Roles

`super_admin` · `ops_admin` · `support_agent` · `finance_admin` · `read_only`

See `StaffPermission`. Support — the biggest team, working fastest — cannot suspend salons,
change platform settings or create staff. Not distrust: a mistake made at speed should have a
small blast radius. `read_only` deliberately excludes `user:pii_reveal`.

### Guard rails

- Suspending or offboarding **revokes every session immediately**. Waiting for a token to
  expire is not good enough on the afternoon you let someone go.
- You cannot change your own status.
- You cannot deactivate the **last active superadmin** — that's unrecoverable without database
  access, and easy to do at speed.
- `reissue` clears the password *and* the TOTP secret (losing a phone is the usual reason) and
  ends every session. **It is the only reset path** — there is deliberately no self-service
  "forgot password" email, because a reset link to a compromised inbox defeats two-factor.

---

## 4b. Console endpoints (Session 21)

Everything below is under `/api/v1/admin`, on the staff filter chain.

| Method | Path | Role | Notes |
|---|---|---|---|
| `GET` | `/ops/summary` | any staff | Queues, not metrics. Three counters still hardcoded — see below |
| `GET` | `/salons/reviews?status` | any staff | Pending oldest-first |
| `POST` | `/salons/reviews/{id}/decision` | ops, super | **Rejection requires a note** |
| `POST` | `/salons/{id}/enqueue-review` | SERVICE | Called by bmp-salon on creation; idempotent |
| `GET` | `/users?q=+91…` | any staff | **Search only**, and the search itself is audited |
| `POST` | `/users/{id}/reveal` | ops, super, support | One field, with a justification, audited |
| `GET` | `/users/{id}/account-health` | ops, super, support | Partial — see below |
| `GET/POST` | `/data-requests` | ops, super, support | Soonest deadline first |
| `POST` | `/data-requests/{id}/verify-identity` | ops, super | Must record HOW |
| `POST` | `/data-requests/{id}/complete` | ops, super | **Refuses unless identity verified** |
| `POST` | `/data-requests/{id}/reject` | ops, super | Needs a reason |
| `GET` | `/audit?action&actor` | ops, super, finance | Read-only; DB revokes UPDATE/DELETE |
| `GET` | `/support/tickets` | any staff | Breached → priority → oldest |
| `GET/POST` | `/support/tickets/{id}/messages` | ops, super, support | `internalNote` never reaches the customer |
| `PATCH` | `/support/tickets/{id}` | ops, super, support | Assign, re-prioritise, resolve |

### A hole this pass closed

`SupportTicketController` at `/api/v1/support-tickets` sits **outside** the `/api/v1/admin/**`
matcher, so it fell through to bmp-common's shared chain — which accepts a **customer** token.
Any logged-in customer could have listed and edited every support ticket on the platform,
including internal notes about other customers. It predates the console's auth and was never
reachable from the app, which is why nobody noticed. It is now `ROLE_SERVICE` only.

### Session 23 — the rest of it

| Method | Path | Role | Notes |
|---|---|---|---|
| `GET/PUT` | `/settings` · `/settings/{key}` | ops, super | Kill switch. Justification required, WARN-logged |
| `POST` | `/users/{id}/unlock` | ops, super, support | Clears the lockout; does **not** send a code |
| `POST` | `/users/{id}/resend-otp` | ops, super, support | To the address already on file — no destination param exists |
| `GET` | `/bookings?q=` · `/bookings/{id}/events` | staff | Reference search + event trail |
| `POST` | `/bookings/{id}/cancel` | ops, super, support | Acts as the customer; reason required |
| `GET/POST` | `/refunds` · `/refunds/{id}/decision` | see below | Four-eyes rule |
| `GET/POST` | `/content-reports` · `/{id}/resolve` | ops, super, support | Note required on **both** outcomes |

**`ops/summary` is now real.** All seven numbers, where four were hardcoded zeros. Today's
bookings returns **-1** if bmp-booking is unreachable — a zero there reads as a dead platform
and sends someone hunting an outage that isn't there.

**The SLA clock is now started.** `first_response_due_at` was never set on ticket creation, so
the breach query matched nothing and the "overdue" counter read zero forever while customers
waited. A metric structurally incapable of being non-zero is worse than no metric, because
people trust it.

**Refunds: you cannot approve one you raised.** "Two people saw this" is the cheapest control
over money there is, and the day one account is compromised it's the only thing between an
attacker and the refund queue. Superadmin can override when there's genuinely nobody else, and
it logs at WARN when they do. Approved requests land in `blocked`, not `paid` — that's not a
rejection, it's "agreed, but there's no payment system to return money through yet".

### Honestly incomplete, and why it's shaped that way

- **`account-health`** now returns real lockout state from bmp-auth. Email-bounce state is still
  `false` always, because bmp-notification doesn't record delivery outcomes — that means "no
  known failure", not "confirmed working". If bmp-auth is unreachable the login fields come back
  **empty** rather than defaulted to "fine": an agent told "not locked" by a system that doesn't
  know will confidently tell a customer the wrong thing.
- **Data-request `complete`** deactivates the account for an erasure. Full anonymisation —
  stripping personal fields while KEEPING booking records, which the law requires us to retain —
  isn't built, and the completion note records exactly what was actually done.
- **Ticket replies are recorded but never delivered.** No email goes out. Flagged loudly in the
  code, because an agent who believes they've replied while the customer heard nothing is worse
  than having no reply feature.
- **Upholding a content report records the decision but doesn't hide the content.** The service
  that owns it has to act, and that call isn't wired — so a moderator can mark something removed
  and it stays visible. Flagged rather than left to be discovered.
- **No refund can be paid.** By design until bmp-payment exists.
- Ticket `requesterName` and user `bookingCount` are still unresolved; the audit list still
  filters in memory (capped at 500).

---

## 4c. Supporting endpoints in other services (Session 21)

The console reads through bmp-admin, which reads through these. All `ROLE_SERVICE` only.

**bmp-auth** — `/api/v1/auth/internal/**`
- `GET /otp-state/{userId}` — lockout, failed attempts, last code sent/expiry, active session
- `POST /unlock/{userId}` — clears the lockout, **does not send a code**
- `POST /resend-otp/{userId}` — to the address **already on the account**

There is deliberately no endpoint that returns a code, and none that accepts a destination.
Redirecting a login code is account takeover with extra steps; reading one is worse.

**bmp-booking** — `/api/v1/bookings/internal/**`
- `GET /search?q=` — by booking reference only, min 3 chars, capped at 25
- `GET /by-customer/{id}` · `GET /count-today`

Reference-only search is deliberate: free-text search over bookings is a browsing tool, and
browsing customer records is the thing an internal console shouldn't make easy. Cancel and the
event trail already work for bmp-admin through the existing controller, which lets
`ROLE_SERVICE` through — duplicating them would create two paths to keep consistent.

**bmp-salon** — `/api/v1/salons/internal/**`
- `PUT /{salonId}/status` — **this is what actually makes a salon visible**
- `GET /{salonId}/support-summary` — diagnoses "why aren't we getting bookings?"

### Two real bugs this pass found

**1. The moderation gate was decorative.** `SalonService.near()` — the customer-facing proximity
search — called `findAll()` and returned every salon regardless of status. Salons are created
`pending` precisely so a human can check them first, but nothing ever filtered on it: anyone who
finished the signup wizard was instantly live to customers under BMP's brand. Now filtered.

Note it accepts **both** `approved` and `active`: the moderation flow writes the former, while
`seed/dev-seed.sql` and pre-Session-21 rows use the latter. Filtering on one alone would have
silently emptied every local dev environment. A migration normalising them is the proper fix.

**2. Nothing enqueued salons for review.** `salon_review` rows were only ever created by an
endpoint no one called, so the queue would have stayed permanently empty while owners waited to
be approved — indistinguishable, from their side, from being ignored. bmp-salon now calls
`enqueue-review` on creation (best-effort, idempotent, loudly logged on failure).

And approving now enacts the decision: `SalonModerationService` calls bmp-salon **before**
recording it, so a review row can never say "approved" beside a salon that's still invisible.

---

## 5. Audit

`AuditLogService.record(...)` now takes the actor's email and role — denormalised, so entries
stay readable after someone leaves — plus a justification, required for anything that reveals
personal data.

Audit writes **never throw**. A logging failure must not roll back the action it describes;
losing an entry is bad, taking the console down is worse. Failures are logged loudly.

`UPDATE` and `DELETE` are revoked on `admin_schema.audit_log` at the database level (V002).

---

## 6. Verify before merging

None of this has been compiled — the sandbox has no JDK. Alongside
`docs/VERIFY_SESSIONS_15_19.md`:

```bash
mvn -q -pl bmp-admin -am clean compile
```

Then:

- [ ] `V003` and `V004` apply cleanly, including on a database that already has V002 data
- [ ] Bootstrap sets the password; a second restart logs "already claimed" and changes nothing
- [ ] First login returns `TOTP_ENROLMENT_REQUIRED`, not a session
- [ ] A challenge token sent as `Authorization: Bearer` is rejected (it's not `stage=session`)
- [ ] A **customer** JWT sent to `/api/v1/admin/**` is rejected
- [ ] Create an employee → account is `invited` → code activates it → login forces TOTP
- [ ] Suspending someone kills their session on the next request
- [ ] `POST /staff` as an `ops_admin` returns 403

`TotpService` is worth a unit test against the RFC 6238 test vectors — it's the one piece here
where a subtle bug is invisible until someone can't log in.
