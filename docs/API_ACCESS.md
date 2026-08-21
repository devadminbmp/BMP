# API access register — what, why, and who

Every endpoint on the platform, with the rule that guards it and the reason for that rule.

**This is the reference for "can X do Y?"** It is generated from a scan of the controllers and
kept in step by hand — if the code and this file disagree, **the code wins and this file is a
bug**. Re-run the scan in §9 after any change to a controller.

Companion docs: `PENDING_WORK.md` (what's missing), `ADMIN_CONSOLE_API.md` (the console
contract), `../CONTEXT.md` (why the platform is shaped like this).

---

## 1. How access is decided, in three layers

A request has to survive all three. None of them is sufficient alone.

| Layer | Where | Answers |
|---|---|---|
| 1. Is a credential needed at all? | `bmp.security.public-paths` per service | "Can an anonymous caller reach this path?" |
| 2. Is the credential valid? | `JwtAuthFilter` (bmp-common) | "Is this token real and unexpired?" |
| 3. Is this caller allowed to do this? | `@PreAuthorize` per endpoint | "Right role? Right salon? Their own record?" |

**Layer 1 is path-based, not method-based.** `/api/v1/salons` is both "search nearby" (public)
and "create a salon" (owner only). Listing it as a public path permits the *path*; the write is
stopped by layer 3. Anyone reading only the YAML will misjudge what is exposed.

### The rule that used to break this

`CommonSecurityConfig` defaulted `public-paths` to `/**`. A service that never set the property
authenticated **nothing**. When measured, **52 endpoints across four services required no
credential of any kind** — none of it deliberate, all of it the same omission repeated.

Session 29 removed the default. A service that omits the property now **fails to start**. If you
are here because a service won't boot, that is this working — add an explicit list.

---

## 2. The identities

| Principal | Issued by | Carries | Reaches |
|---|---|---|---|
| `ROLE_CUSTOMER` | bmp-auth | userId | Their own data, public reads |
| `ROLE_SALON_OWNER` | bmp-auth | userId, **salonId** | Everything for *their* salon |
| `ROLE_MANAGER` | bmp-auth | userId, **salonId** | Day-to-day ops for *their* salon |
| `ROLE_STYLIST` | bmp-auth | userId, **salonId** | Their own schedule and availability |
| `ROLE_SERVICE` | the shared internal key | nothing | Service-to-service only |
| Staff (5 roles) | **bmp-admin, different signing key** | staff id, role | `/api/v1/admin/**` only |

**`salonId` in the token is what makes multi-tenancy work.** `hasRole('SALON_OWNER')` on its own
is nearly useless — every salon owner in Bengaluru holds that role. The full rule is always:

```java
hasAnyRole('SALON_OWNER','MANAGER')
  and principal.salonId() != null
  and principal.salonId().equals(#salonId)
```

The role says what *kind* of thing you are; `salonId` says *which one*. Omitting the second half
is the most repeated mistake in role-based systems, and it is invisible in testing because you
usually test with one tenant.

**Staff tokens are signed with a different key and carry a `bmp-admin` audience.** A customer
token cannot become a staff token, and vice versa. This is why the console is a separate app.

---

## 3. bmp-auth — identity (port 8081)

| Method | Path | What it is | Who | Why |
|---|---|---|---|---|
| POST | `/auth/otp/request` | Send a login code | **Public** | You cannot require a login to log in. Rate-limited; a resend cooldown is enforced server-side. |
| POST | `/auth/otp/verify` | Redeem the code → tokens | **Public** | Same. 5 wrong attempts locks the account for 15 minutes. |
| POST | `/auth/oauth2/google` | Google sign-in | **Public** | Same. |
| POST | `/auth/refresh` | New access token | **Public** | The refresh token *is* the credential. |
| POST | `/auth/logout` | Revoke a refresh token | **Public** | Deliberate: someone with a leaked token must be able to revoke it without first proving who they are. |
| GET | `/auth/me` | Who am I | Any signed-in user | Reads the token; returns only that user. |
| GET/POST | `/auth/internal/otp-state`, `/unlock`, `/resend-otp` | Support account recovery | `SERVICE` | Called by bmp-admin when an agent unlocks someone. **Never returns or emails the OTP** — an endpoint that hands a code to staff is an endpoint that impersonates a customer. |

---

## 4. bmp-user — profiles (port 8082)

Every endpoint is `SERVICE` **or the user themselves** (`principal.userId() == #userId`).

| Method | Path | What it is | Who |
|---|---|---|---|
| POST | `/users` | Create | `SERVICE` — signup goes through bmp-auth |
| GET | `/users/{id}` | Read profile | `SERVICE` or self |
| GET | `/users?phone=` | Look up by phone | `SERVICE` only |
| PUT | `/users/{id}` | Edit profile | `SERVICE` or self |
| POST | `/users/{id}/deactivate` | Close account | `SERVICE` or self |
| POST | `/users/{id}/reactivate` | Reopen | `SERVICE` only — you cannot reactivate yourself |
| POST/GET/DELETE | `/users/{id}/roles…` | Role grants | `SERVICE` for writes, self for reads |
| PUT/GET/DELETE | `/users/{id}/onboarding-state` | First-run progress | `SERVICE` or self |

**Why phone lookup is service-only:** it turns a phone number into a user id, which is a
membership oracle — "is this number registered with BMP?" That belongs behind a service call
that can be audited, not in front of anyone with a token.

**There is no browsable user list**, by design. A list endpoint invites idle browsing of
personal data. Staff search through bmp-admin, which masks PII and audits every reveal.

---

## 5. bmp-salon — supply (port 8083)

### Public — guests browse before logging in

This is the product model: the web app lets people browse and only meets the login gate at
**Book**. Requiring a token to look at salons would move the wall earlier, which is exactly
where bookings get abandoned.

| Method | Path | What it is | Why public |
|---|---|---|---|
| GET | `/salons` | Search nearby | The landing experience. Only `approved`/`active` salons are returned. |
| GET | `/salons/{id}` | Salon detail | The public salon page. |
| GET | `/salons/{id}/services` | Menu and prices | On that page. |
| GET | `/salons/{id}/stylists` | Who works here | On that page. |
| GET | `/stylists/{id}` | Stylist profile | Ratings are public by design — that's the point of a marketplace. |
| GET | `/availability/slots`, `/slots/any` | Free times | A guest picks a time, *then* logs in. Reveals no more than a phone call would. |

> **Known minor exposure:** `/salons/{id}/stylists` returns `stylistUserId` (added Session 26 so
> the stylist dashboard can identify its own user). It is an opaque UUID that grants nothing —
> every user endpoint requires `SERVICE` or self — but it is more than a guest needs. Narrowing
> it needs two DTO shapes; recorded here rather than left unstated.

### Salon-scoped writes — owner and/or manager **of that salon**

| Method | Path | Who | Why that split |
|---|---|---|---|
| POST | `/salons` | `SALON_OWNER` | Creating your salon. |
| PUT | `/salons/{id}` | Owner **or** manager | Name, description, location — day-to-day. |
| POST | `/salons/{id}/policy` | **Owner only** | Sets what customers are charged for late cancellation. A commercial decision, frozen onto every later booking — not a floor-management one. |
| PUT | `/salons/{id}/hours` | Owner or manager | "We're closing early for a power cut" is exactly a manager's job. |
| POST | `/salons/{id}/services` | Owner or manager | Sets a **price** customers can book at. |
| POST | `/salons/{id}/stylists` | Owner, manager, or `SERVICE` | Decides who is bookable here. |
| PUT | `/salons/{id}/stylists/{sid}/available-today` | Owner, manager, **or stylist** | "I'm not in today" is the stylist's own call. See the caveat below. |
| POST | `/salons/{id}/stylists/{sid}/alumni` | **Owner only** | Ends an employment relationship, freezes ratings permanently, and **cannot be undone**. |
| POST | `/salons/{id}/stylists/{sid}/services` | Owner or manager | Can carry a per-stylist price override. |
| POST | `/availability/walk-in` | Owner or manager | Blocks a stylist's calendar. Unprotected, this was a way to take the platform's supply offline silently. Stylists are excluded: blocking their own time is the *time-off* flow, which records it as such. |
| All | `/salons/{id}/combos/**` | Owner or manager | Class-level rule. Reads are included because combos have **no customer-facing UI at all** — widening access for a hypothetical consumer is how things end up open. |
| POST | `/salons/{id}/invites`, GET/DELETE invites | Owner or manager | Staffing the floor. |
| GET/POST/DELETE | `/salons/{id}/staff…` | **Owner only** | Managers cannot appoint or remove managers. |
| All | `/salons/{id}/stylists/{sid}/availability/**` | Owner or manager | Working hours and time off. |
| POST/GET | `/salons/internal/**` | `SERVICE` | Moderation status writes and support lookups, from bmp-admin. |

> **Caveat on `available-today`:** the rule is salon-scoped but not narrowed to "your own row".
> A stylist's token carries their **user** id; the path carries the salon-side **stylist** id —
> two different identifiers, with no way to compare them in SpEL. A stylist can therefore flip a
> colleague's availability at the same salon. That is a nuisance, not a breach, and the
> alternative was leaving it open to everyone. Proper narrowing needs the check inside the
> service, where the mapping can be loaded.

---

## 6. bmp-booking — demand (port 8084)

**Nothing here is public.** You must be signed in to see or touch any booking.

| Method | Path | What it is | Who |
|---|---|---|---|
| POST | `/bookings` | Create a booking | Signed-in customer, **for themselves only** |
| GET | `/bookings/{id}` | One booking | The customer who made it, **or staff of that salon** |
| GET | `/bookings?customerId=` | My bookings | Yourself only |
| POST | `/bookings/{id}/cancel` | Cancel | The customer. A salon cancelling on a customer's behalf is deliberately **not a modelled move** |
| GET | `/bookings/{id}/events` | Audit trail | Customer or salon staff |
| GET | `/bookings/salon/day`, `/salon` | The desk, and the salon's history | `SALON_OWNER`, `MANAGER`. Takes no `salonId` — it comes from the JWT claim, so there is no parameter to tamper with. Customer phone numbers in these responses are **masked**. |
| PUT | `/salons/{salonId}` | Edit the salon's profile | **Session 41 — this had NO authorization at all and accepted `status`, so any logged-in user could approve their own salon and rename anyone else's.** Now `SALON_OWNER` + `principal.salonId().equals(#salonId)`, and `status` returns 403: approval is moderation, not a field. |
| PUT | `/reviews/{reviewId}` | Edit your own review, inside the window | **Session 41 — was reachable with NO CREDENTIAL.** `public-paths` has `/api/v1/reviews/*` for the public GET, and those patterns are **method-blind**. Now `CUSTOMER` + author check against `review.author_user_id` (V004). Pre-V004 rows are refused. |
| POST/PUT | `/reviews/{reviewId}/response` | The salon's public reply | **Session 41 — needed a login and nothing more, so any customer could reply as any salon.** Now `SALON_OWNER`/`MANAGER` + the review's `salonId` must match the caller's claim. |
| GET | `/salons/{id}/detail` | The customer's salon page — services, stylists, categories, cheapest price, readable opening hours | **Public.** Browsing must work before sign-in (browse freely, gate at Book). Only PUBLICLY VISIBLE salons; 404 otherwise, because an approved-looking page for an unapproved salon is worse than no page. Session 40 — the FE had been parsing `/salons/{id}` and throwing, so this screen had never rendered live. |
| GET | `/salons?near=&radiusKm=&category=` | The discovery list | **Public.** `category` filters server-side — filtering in the client means downloading every salon in the city to show the four that do nails. Session 40 grew the response from 3 fields to 9. |
| GET | `/bookings/salon/upcoming` | What's coming in, soonest first | `SALON_OWNER`, `MANAGER`, salon from JWT. NOT history with a filter — history orders by when the booking was MADE. Cancelled/completed/no-show excluded: a work queue padded with things that aren't happening stops being trusted. |
| POST | `/bookings/{id}/salon-cancel` | The salon genuinely can't serve the appointment | `SALON_OWNER`, `MANAGER` + `requireSameSalon`. **Always fee-free**, enforced in `CancellationTerms` before any policy band — no salon setting can charge a customer for the salon's problem. Reason required (5+ chars) and sent to the customer. |
| POST | `/bookings/{id}/reschedule` | Customer moves their own booking | Self or `ROLE_SERVICE`. Limited by the salon's notice period and reschedule cap, both **frozen at booking time**. Does NOT reset the cancellation clock. New slots validated against the availability algorithm exactly like a new booking. |
| POST | `/bookings/{id}/salon-reschedule` | Salon moves a booking | `SALON_OWNER`, `MANAGER` + `requireSameSalon`, AND the salon's `salonCanRescheduleDirectly` (off by default). Reason required. Doesn't consume the customer's allowance. |
| GET | `/bookings/{id}/cancel-preview` | What cancelling would cost | Customer or that salon's staff. Read-only; runs the identical calculation the real cancellation does. Refund figure is ADVISORY until payments exist. |
| GET | `/bookings/{id}/reschedule-eligibility` | Can this still be moved? | Customer or that salon's staff. Asked before showing the button, so the app never offers an action about to 409. |
| GET | `/bookings/salon/customer/{customerId}` | One customer's history **at your salon** — visits, spend, cancellations, no-shows, usual stylist | `SALON_OWNER`, `MANAGER`. The salon half of the pair comes from the JWT, so there is no request that returns this customer's bookings at a *different* salon. Enforced twice: in the URL shape, and at the query (`findBySalonIdAndCustomerId`, which has no single-argument variant). Phone is masked. **404** rather than an empty summary when they've never booked here — a 200-with-zeros for any well-formed UUID lets a salon test ids and learn which are real BMP customers. |
| POST | `/bookings/{id}/reveal-contact` | Hand the salon the customer's real number so they can ring about a delay | `SALON_OWNER`, `MANAGER` + `requireSameSalon`. **Not stylists** — the front desk makes the calls, and every role added here is another person who can enumerate the salon's customers. A reason is required. Writes `CONTACT_REVEALED` to `booking_events`, which the **customer** can read via `GET /bookings/{id}/events`. POST rather than GET because it writes every time it's called. No rate limit yet — see PENDING_WORK N4b. |
| POST | `/bookings/{id}/arrive`, `/start`, `/complete`, `/no-show` | Move the booking forward | `SALON_OWNER`, `MANAGER` — the salon-actor transitions in the locked state machine |
| GET | `/bookings/internal/**` | Search, by-customer, count | `SERVICE` |

The customer-facing five enforce ownership **in the method body** (`requireSelfOrService`,
`requireCustomerOrSalonStaff`) rather than by annotation, because the rule is "self *or* staff of
the salon this booking belongs to" — which needs a database lookup that SpEL cannot do.
**A scan for `@PreAuthorize` will report these as unprotected. They are not.**

Before Session 21 this controller had **no authorization at all**: any customer could read or
cancel any booking by id.

---

## 7. bmp-payment, bmp-review, bmp-rewards, bmp-notification

### bmp-payment (8085) — `SERVICE` only, entire service

A customer's app talks to the payment gateway's SDK; the gateway talks to us. No end-user role
belongs here, **not even for reads** — a payment order reveals what someone paid, for what, and
the commission split. The rule is at class level so a method added later inherits it.

⚠️ `PUT /payment-orders/{id}/status` is a manual stand-in for the Razorpay webhook and can mark
a payment **captured**. It needed no credential at all before Session 29 — on a public server,
that is "book anything for free". **Delete it when the real webhook lands.**

### bmp-review (8086)

| Method | Path | Who |
|---|---|---|
| GET | `/salons/{id}/reviews`, `/reviews/{id}` | **Public** — a rating nobody can see is worthless |
| POST | `/bookings/{id}/review` | Signed-in customer |
| PUT | `/reviews/{id}` | The author |
| POST/PUT | `/reviews/{id}/response` | Salon owner/manager of the reviewed salon |

⚠️ **Nothing verifies the reviewer ever had a booking** (`ReviewService:37`). Requiring a token
stops anonymous spam; it does not stop an account reviewing a salon it never visited. On a
marketplace whose value is trustworthy ratings, this is the most important gap in this file.
Tracked in `PENDING_WORK.md` §S3.

### bmp-rewards (8087)

| Method | Path | Who | Why |
|---|---|---|---|
| POST | `/coupons` | `SERVICE` | **Mints money.** Any customer could previously create a 100%-off coupon. Staff issue via bmp-admin, which applies `CouponIssuePolicy`. |
| POST | `/coupons/validate`, `/coupons/quote` | Signed in | A read. Not public — anonymous access lets you brute-force the coupon namespace. |
| GET | `/users/{id}/wallet`, `/wallet/transactions` | `SERVICE` or **self** | Was readable for *any* user id by changing the UUID — the classic IDOR. |
| POST | `/admin/wallet/credit` | `SERVICE` | Creates money from nothing. **A path is not a permission** — the `/admin/` prefix protected nothing. |
| GET | `/referrals/my-code` | Signed in | Yours, from your token. |
| POST | `/coupons/internal/redeem`, `/release`, `/referrals/internal/attribute` | `SERVICE` | Called inside the booking transaction. |
| All | `/internal/coupons/**` | `SERVICE` | bmp-admin issuing on a staff member's behalf. |

### bmp-notification (8089) — `SERVICE` only, entire service

The log records who was sent what, at which number and email, and when. **It is worse to leak
than most of the tables it describes:** a booking row says someone had a haircut; this says
their phone number and that they were reminded on Tuesday. All seven endpoints were open before
Session 29, including the whole table and one person's entire message history.

---

## 8. bmp-admin — staff console (port 8088)

Different signing key, `bmp-admin` audience, `/api/v1/admin/**` on its own filter chain. Five
staff roles; the permission map is `StaffPermission.java`, mirrored in `BMP-ADMIN/src/api/mocks.ts`.

| Permission | super | ops | support | finance | read-only |
|---|:--:|:--:|:--:|:--:|:--:|
| `salon:view` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `salon:review` (approve/reject) | ✓ | ✓ | | | |
| `user:view` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `user:pii_reveal` | ✓ | ✓ | ✓ | | |
| `booking:view` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `refund:issue` | ✓ | | | ✓ | |
| `ticket:work` | ✓ | ✓ | ✓ | | |
| `content:moderate` | ✓ | ✓ | ✓ | | |
| `data_request:fulfil` | ✓ | ✓ | | | |
| `settings:manage` (kill switch) | ✓ | ✓ | | | |
| `audit:view` | ✓ | ✓ | | ✓ | |
| `staff:manage` | ✓ | | | | |

Superadmin is a **wildcard** server-side, not an enumerated list: an enumerated superadmin
silently fails to receive each new permission until someone remembers to add it.

Notable rules beyond the table:

- **Four eyes on refunds.** You cannot approve a refund you raised. Applies to everyone.
- **PII reveal costs a typed justification** and writes an audit row naming the staff member,
  the customer, the field and the reason.
- **Support can only issue coupons to selected users, against a ticket.** A support agent must
  not be able to discount the platform from a chat window. Enforced in `CouponIssuePolicy`
  server-side, not by hiding a form field.
- **`/api/v1/admin/auth/*` is public** — login, TOTP, activation, refresh. Same reason as
  bmp-auth: you cannot require a session to create one.

### ⚠️ Three controllers live OUTSIDE the admin chain

`/api/v1/staff`, `/api/v1/audit-log` and `/api/v1/support-tickets` are **not** under
`/api/v1/admin/**`, so `AdminSecurityConfig` never saw them. They fell to the shared chain,
which accepts **any valid JWT — including a customer's**.

**`POST /api/v1/staff` creates a BMP staff account.** Until Session 29, any logged-in customer
could create themselves one. Not a chain of steps — one call. `/api/v1/audit-log` exposed the
record of which staff member viewed which customer's phone number and why.

All three are now `SERVICE`-only and `@Deprecated(forRemoval = true)`. They are superseded by
`/api/v1/admin/staff`, `/api/v1/admin/audit` and `/api/v1/admin/support/tickets`, which the
console actually uses. **They should be deleted** — see `PENDING_WORK.md` §4.

**The pattern is the lesson.** This is the second time (after `SupportTicketController` in
Session 20) that a controller sitting outside an intended matcher turned out to be wide open. A
`securityMatcher` protects a *path prefix*, not a *service*. Any new controller in bmp-admin
must live under `/api/v1/admin/**` or carry its own rule.

---

## 9. Re-running the scan

After changing any controller:

```bash
cd BMP
python3 - <<'EOF'
import re,glob,os
for f in sorted(glob.glob('*/src/main/java/**/controllers/*.java',recursive=True)):
    svc=f.split('/')[0]; t=open(f,encoding='utf-8').read()
    base=re.search(r'@RequestMapping\("([^"]+)"\)',t); base=base.group(1) if base else ''
    head=t[:t.index('public class')] if 'public class' in t else ''
    cls=re.search(r'@PreAuthorize\("([^"]+)"\)',head)
    for c in re.split(r'\n\n(?=\s*(?:/\*\*|@Operation|@PreAuthorize|@(?:Get|Post|Put|Patch|Delete)Mapping))',t):
        m=re.search(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*"([^"]*)")?',c)
        if not m: continue
        pre=re.search(r'@PreAuthorize\("([^"]+)"\)',c)
        rule=pre.group(1) if pre else (cls.group(1) if cls else 'NONE')
        print(f"{svc:18} {m.group(1).upper():6} {base+(m.group(2) or ''):58} {rule}")
EOF
```

`NONE` is not automatically a bug — auth endpoints are public by design, and bmp-booking
enforces ownership in the method body. But **every `NONE` needs a reason, and the reason belongs
in this file.** If you can't write the reason, that's the finding.

Also check every service still declares `public-paths`:

```bash
python3 -c "
import yaml,glob
for f in sorted(glob.glob('*/src/main/resources/application.yml')):
    d=yaml.safe_load(open(f)) or {}
    pp=(d.get('bmp') or {}).get('security',{}).get('public-paths')
    print(f.split('/')[0], 'OK' if pp else 'MISSING — will not start')"
```
