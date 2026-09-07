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
| GET | `/availability/salon-day` | Who's free today, batched | Session 52. Same disclosure as the two above — who works here and when they're free — in ONE call instead of one per stylist. Public for the same reason: gating it moves the login wall to before "can you fit me in?", which is where bookings get abandoned. |

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
| POST | `/bookings/counter` | Owner or manager of THIS salon | Session 52. Takes a real booking for someone with no BMP account. The salon comes from the **token**, never the body — a `salonId` here would let one salon write bookings, and contact records, into another's diary. The customer record is created server-side from the name and phone, so a manager cannot attach a booking to a customer id they typed. Stylists are excluded: taking bookings and holding contact details is the desk's job. |
| GET/POST | `/salon-customers`, `/salon-customers/{id}` | Owner or manager of THIS salon | Session 52. The salon's own contact book. **No `salonId` parameter on any customer-facing route** — a contact list is the most saleable thing a salon owns, and a parameter here would let any owner page through a competitor's regulars with names and numbers. Not visible to stylists, consistent with Sessions 48/49. |
| POST | `/salon-customers/internal/{salonId}/**` | `ROLE_SERVICE` | Called by bmp-booking during a counter booking. `salonId` IS a parameter here because a service caller has no salon of its own; safe only because `ROLE_SERVICE` comes from the internal key, and bmp-booking passes the salon it took from the manager's token. |
| POST | `/bookings/{id}/review` | `ROLE_CUSTOMER` **and** the booking must be yours | Session 54. Previously any customer could review any booking id, including invented ones. Now verified against bmp-booking: exists / yours / COMPLETED / within 90 days. Salon and stylist resolved from the booking, not the body. **Routed to bmp-review**, not bmp-booking — see the gateway comment. |
| GET | `/bookings/{id}/review` | Author only | Session 54. "Have I reviewed this yet?" 404 both when no review exists and when one exists but isn't yours — a booking id must not reveal somebody else's review. |
| PUT | `/salon-customers/{id}` | Owner or manager of THIS salon | Session 53. Name, email and note only — the PHONE is deliberately not editable, being the unique key the customer's whole visit history was matched by. |
| GET | `/bookings/salon/counter-customer/{id}` | Owner or manager of THIS salon | Session 53. One counter customer's visits here. Salon-scoped from the token; 404 on none, so one salon cannot probe for another's customer ids by watching which return 200. |
| GET | `/salons/internal/stylist-contact/{id}` | `ROLE_SERVICE` | Session 53. Name + userId only, so bmp-booking can resolve an address to notify a stylist. Deliberately narrower than `/stylists/{id}`: a booking service has no business holding somebody's moderation history. |
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

## 8b. Sessions 48–49 — stylist self-service, leave, the booking window, invoices

Everything added since the table above was last regenerated. Same rule as the rest of this file:
**every endpoint gets a reason, and if you can't write the reason that's the finding.**

### bmp-salon — a stylist's own account (`/api/v1/stylist-profile/**`)

Scoped entirely from the token. **No endpoint here takes a stylist id**, so there is nothing in a
path or body to swap for a colleague's — the shape of hole this codebase keeps finding
("authorise the path, then trust the body") is absent because there is no id to trust.

| Method + path | Guard | Why |
|---|---|---|
| `POST /stylist-profile` | `isAuthenticated()` | A **customer** presses "I'm a stylist". Requiring the stylist role to become one would be a door openable only from inside. Grants no salon access. |
| `GET/PUT /stylist-profile` | `isAuthenticated()` | Their own record. Editable with no salon attached — the profile is theirs, not the salon's. |
| `GET /stylist-profile/salons` | `isAuthenticated()` | Work history, including alumni. That list is what survives changing jobs. |
| `POST /stylist-profile/leave` | `hasRole('STYLIST')` | Ask for time off. Blocks nothing until approved. |
| `GET /stylist-profile/leave` | `hasRole('STYLIST')` | Their own requests. |
| `DELETE /stylist-profile/leave/{id}` | `hasRole('STYLIST')` | Withdraw — works **after** approval too; ownership re-checked against the caller. |
| `GET/PUT /stylist-profile/available-today` | `hasRole('STYLIST')` | The salon-scoped version lists STYLIST **and** requires `principal.salonId()`, which a stylist's JWT never has — so it could never pass for the role it names. |
| `POST /stylist-profile/join-requests` | `isAuthenticated()` | Ask a salon to add you. Creates **no** link. |
| `POST /stylist-profile/leave` etc. | — | All resolve the stylist from `caller.userId()`. |

> **Path note.** These live under `/stylist-profile`, **not** `/stylists/me`.
> `/api/v1/stylists/*` is in bmp-salon's **public-paths** (it serves a stylist's page to browsing
> customers) and a single Ant `*` matches one segment — so `/stylists/me` would have had the JWT
> filter skipped entirely and run with no principal.

### bmp-salon — the salon's side

| Method + path | Guard | Why |
|---|---|---|
| `GET /salons/{id}/join-requests` | owner/manager **of that salon** | Adding someone to the floor is day-to-day work. |
| `POST /salons/{id}/join-requests/{r}/decide` | owner/manager of that salon | **Accepting is the only thing that creates a `stylist_salon` row.** Decline requires a note. |
| `GET /salons/{id}/leave-requests` | owner/manager of that salon | Soonest first — next week needs answering before next month. |
| `POST /salons/{id}/leave-requests/{r}/decide` | owner/manager of that salon | **Approving writes the blocking availability rows.** Decline requires a note. |
| `DELETE /salons/{id}/leave-requests/{r}` | owner/manager of that salon | Revoke an approval; removes the rows. |
| `POST /salons/{id}/policy` | `hasRole('SALON_OWNER')` + own salon | Carries the V022 booking window. **`commissionBps` is refused here** — Session 48 closed that money hole. |
| `GET /salons/internal/stylist-by-user/{userId}` | `hasRole('SERVICE')` | bmp-booking resolving a stylist's scope. **This call IS the authorization** for the stylist schedule endpoints. |

### bmp-booking — the stylist's schedule, and money

| Method + path | Guard | Why |
|---|---|---|
| `GET /bookings/stylist/day` | `hasRole('STYLIST')` | Own appointments. **No `stylistId` parameter** — resolved from the token via bmp-salon, so there is nothing to change to read a colleague's day. |
| `GET /bookings/stylist/upcoming` | `hasRole('STYLIST')` | As above. |
| `GET /bookings/stylist/history` | `hasRole('STYLIST')` | As above. |
| `GET /bookings/{id}/invoice` | `hasRole('CUSTOMER')` | Own bill. Ownership checked against the **invoice's** frozen customer, not the booking's. 404 (not 403) on mismatch, so a bill's existence can't be probed. |
| `GET /salons/{s}/bookings/{b}/invoice` | owner/manager of that salon | The salon's copy. |
| `POST /salons/{s}/bookings/{b}/invoice` | owner/manager of that salon | Raise a bill for a pre-feature booking. Idempotent. |
| `POST /salons/{s}/invoices/{i}/payment` | owner/manager of that salon | Record a counter payment. Stores **who** said so. |

> **STYLIST is deliberately absent from every row in the money half of that table.** A stylist has
> no money surface anywhere in BMP — `BookingDtos.StylistScheduleEntry` has no field for a
> customer id, phone, email, surname or any amount — and an invoice endpoint would be the single
> hole in it.

### bmp-review

| Method + path | Guard | Why |
|---|---|---|
| `GET /reviews/stylist/{id}` | `isAuthenticated()` | A stylist's own reviews. Two segments after `/reviews`, so the public-path `/api/v1/reviews/*` does **not** match it. Any signed-in user may read it — review content is public by nature — and the response carries **no author identity**, so a stylist cannot work out who left a bad review. |

### Gateway routing is part of the access story

Three salon-shaped paths are served by other services and their routes **must stay declared above
`salon-service`**, which claims all of `/api/v1/salons/**`:

```
/api/v1/salons/*/reviews              -> bmp-review
/api/v1/salons/*/bookings/**          -> bmp-booking
/api/v1/salons/*/invoices/**          -> bmp-booking
```

`GatewayRouteTest.salonShapedPathsGoToTheRightService` pins this by asking which route matches
FIRST for each real path. It exists because the reviews route was mis-pointed from the day the
endpoint was written, and `/api/v1/stylist-profile/**` had no route at all — every Session 48/49
stylist endpoint 404'd at the gateway while working perfectly against the service directly.

---

## 8c. Sessions 50–51 — payments, and platform power over a stylist

### bmp-payment

| Method + path | Guard | Why |
|---|---|---|
| `POST /payment-orders/booking/{bookingId}` | `hasRole('SERVICE')` | Called by bmp-booking at booking time. Freezes the split using the **SALON'S own rate**, never a platform constant. Idempotent — a retried booking returns the existing order rather than failing a customer who did nothing wrong. |
| `POST /payment-orders/webhook` | **PUBLIC — by necessity** | The gateway calls it from its own servers holding no BMP credential, so there is no token to require. **Its only defence is the HMAC-SHA256 signature check**, verified in constant time. An unset webhook secret makes the verifier refuse *everything* rather than accept everything — payments stop, which someone notices in minutes; the alternative is forged webhooks, which nobody notices. |
| `GET /payment-orders/{id}` | `hasRole('SERVICE')` | A payment order reveals what somebody paid and the commission split on it. No end-user role belongs here, not even for reads. |

> **The public path is an EXACT match**, not a prefix. `/api/v1/payment-orders/**` would re-open
> the dev-only status setter that can mark a payment captured — the very hole the file's own note
> records closing.

### bmp-salon — platform power over a stylist (V025)

| Method + path | Guard | Why |
|---|---|---|
| `POST /salons/{id}/stylists/{sid}/alumni` | owner **or manager** of that salon | Employment. Widened from owner-only in Session 51: it was restricted because removal was irreversible, and Session 48 made re-adding restore the same row. |
| `GET /salons/internal/stylists/{id}` | `hasRole('SERVICE')` | The console's read, including suspension state and current salons. |
| `POST /salons/internal/stylists/{id}/suspend` | `hasRole('SERVICE')` | Bars them from BMP. Reason required (min 5 chars) — enforced here, in bmp-admin, and by a CHECK constraint. |
| `POST /salons/internal/stylists/{id}/reinstate` | `hasRole('SERVICE')` | Lifts the bar. |
| `POST /salons/internal/salons/{sid}/stylists/{id}/remove` | `hasRole('SERVICE')` | Admin-initiated employment removal. Routes through the SAME `markAlumni` the owner calls, so "removed" means one thing however it happened. |

### bmp-admin — the console's door to the above

| Method + path | Guard | Why |
|---|---|---|
| `GET /admin/stylists/{id}` | `SUPER_ADMIN`, `OPS_ADMIN`, `SUPPORT_AGENT` | Reading a record is support work. |
| `GET /admin/stylists/suspended` | same | The "who is barred" list. |
| `POST /admin/stylists/{id}/suspend` | `SUPER_ADMIN`, `OPS_ADMIN` | **Not SUPPORT_AGENT.** Ending somebody's ability to earn is not support work — an agent on an angry 9pm call should not be able to bar the person being complained about. |
| `POST /admin/stylists/{id}/reinstate` | `SUPER_ADMIN`, `OPS_ADMIN` | Same reasoning inverted. |
| `POST /admin/salons/{sid}/stylists/{id}/remove` | `SUPER_ADMIN`, `OPS_ADMIN` | Employment, not a ban. |

All five write actions are written to the audit log with the staff member, their role, the IP and
the justification — "who barred this person, and why?" has to outlive the person who decided.

### Enforcement is not in the UI

A suspended stylist is blocked at **four** independent points, via `StylistSuspensionGuard`:
invite redemption, owner add, join-request acceptance, and **availability**. The last matters most
— without it, somebody suspended today keeps taking bookings at the salon they were already on,
which is exactly the case suspension exists for.

---

## 8d. Session 65 — account administration, scoped by whose account it is

Darshan's rule, verbatim: *"number changes, email changes, account block, account remove of
customers can be done by support ... customer and support accounts can be done by ops admin ...
all kind of accounts can be done by main admin."*

### The thing that is easy to get wrong

**There are two account tables, not one.**

| Table | Who is in it | How they sign in | Screen | Rule lives in |
|---|---|---|---|---|
| `user_schema.users` | customers, salon owners, managers, stylists | phone + emailed OTP | Users / Customer help | `AccountScope.java` |
| `admin_schema.bmp_staff` | support agents and leads, finance, ops, the owner | password + TOTP | Staff accounts | `StaffAccountScope.java` |

"Ops admin can manage support accounts" **cannot** be satisfied by the user endpoints, because
support agents have no row there. That half of the requirement is the staff screen, and it needed
its own change: the route was `staff:manage` (owner-only) and had to become `account:manage_staff`.

### Permissions

| Permission | Held by | Means |
|---|---|---|
| `account:manage_customer` | support agent, support lead, ops admin | act on a customer |
| `account:manage_staff` | ops admin | act on salon-side people, and on the support desk |
| `account:manage_any` | super admin only | act on anyone, including other admins |

Finance deliberately has **none**: whoever moves the money should not be able to alter the identity
the money is attached to.

`StaffPermission.permissionsFor(super_admin)` now **derives** its set by reading the constants off
the class instead of listing them. The hand-written list had already drifted — the three
permissions above existed, `has()` granted them to the owner as a wildcard, and `permissionsFor`
did not return them, so the endpoints allowed the action while the console hid the button.

### Endpoints

| Endpoint | Guard | Notes |
|---|---|---|
| `PATCH /api/v1/admin/users/{id}/contact` | authenticated staff, then `AccountScope` | changing the phone changes who can log in. Reason required; old and new both audited |
| `POST /api/v1/admin/users/{id}/block` | authenticated staff, then `AccountScope` | reversible. Bookings and history untouched |
| `POST /api/v1/admin/users/{id}/remove` | authenticated staff, then `AccountScope` | **anonymise, not delete** — past bookings are also the salon's record of paid work |
| `PATCH /api/v1/users/{id}/contact` (bmp-user) | `hasRole('SERVICE')` | internal. Re-checks phone uniqueness against `uk_users_phone` |
| `POST /api/v1/admin/staff` and `/{id}/status`, `/{id}/reissue` | `hasAnyRole('SUPER_ADMIN','OPS_ADMIN')`, then `StaffAccountScope` | ops may act on the desk only; never on an admin, never on themselves |

**`@PreAuthorize` cannot express any of these rules.** It only proves the caller is staff at all.
The real question — *may this caller act on THIS account?* — depends on the **target's** role, which
is unknown until the row is loaded. So every one of the endpoints above loads first and authorises
second, which is the reverse of the usual order and is the point.

### Reasons are mandatory

Every action carries a justification, enforced in the service (the audit column is nullable because
migrations and system actions write rows too). "Phone changed" is not reviewable; "phone changed
from X to Y by this agent because the customer had lost the SIM" is — and it is the only record
that survives if the change turns out to have been social engineering.

## 8e. Session 65 — a block that actually blocks, and self-service contact change

### The Block button shipped doing nothing

It called `deactivate()`, which sets `deactivated_at`. Two consequences, neither obvious:

1. bmp-auth **reactivates** a deactivated account on its owner's next OTP login — Instagram-style
   soft deactivation, written for people who pause their own account. A blocked person logged in
   and was silently unblocked.
2. Neither `/auth/refresh` nor `/auth/me` read `deactivated_at`, so anyone already signed in kept
   working until their refresh token expired — days.

The button worked, the audit entry was written, and nothing happened.

### The fix

`blocked_at` / `blocked_by` / `blocked_reason` on `user_schema.users` (V006), separate from
`deactivated_at` because the two say opposite things about the person's wishes:

| Column | Means | Next login |
|---|---|---|
| `deactivated_at` | "I want a break" | reactivates — correct, unchanged |
| `blocked_at` | "we stopped you" | refused |

Refused in **three** places, all of which had to be found: `verifyOtp` (before the reactivation
line — the ordering IS the fix), `refresh` (which also revokes the token), and `me` (403, not 401,
so the app can say what happened instead of looping through the login screen).

| Endpoint | Guard | Notes |
|---|---|---|
| `POST /api/v1/users/{id}/block` (bmp-user) | `hasRole('SERVICE')` | refuses re-blocking, so a second block cannot overwrite the first one's reason |
| `POST /api/v1/users/{id}/unblock` (bmp-user) | `hasRole('SERVICE')` | does not reactivate a self-deactivated account |
| `POST /api/v1/auth/internal/revoke-sessions/{id}` | `hasRole('SERVICE')` | called right after a block |
| `POST /api/v1/admin/users/{id}/unblock` | staff, then `AccountScope` | same authority as blocking |

**A 15-minute gap remains and is not closed.** Revoking refresh tokens ends the session at the next
renewal; an access token already issued stays valid until it expires (`BMP_ACCESS_TOKEN_TTL_SECONDS`,
900 by default). Stateless JWTs cost this. Closing it needs a revocation check on the resource
services, which bmp-auth cannot do alone.

### Self-service contact change

| Endpoint | Guard | Notes |
|---|---|---|
| `POST /api/v1/auth/contact/request` | `isAuthenticated()` | issues a code; nothing changes yet |
| `POST /api/v1/auth/contact/confirm` | `isAuthenticated()` | single-use, 10 min, 5 attempts |

Neither takes a user id — **the id comes from the token**. An endpoint that accepted one would have
to authorise it, and the day that check is wrong, anybody signed in can point somebody else's
account at their own number. Both are absent from bmp-auth's `public-paths`, so they authenticate
by default.

**What the code proves depends on where it went**, and the API says so via `provesNewNumber`:

- **Email change** → code goes to the NEW address. Real ownership proof.
- **Phone change** → code goes to the address already on file. Proves the requester, not the
  number. SMS is undeliverable until DLT registration completes, so no better proof exists today.
  A typo produces a self-inflicted lockout; the session deliberately survives the change so it can
  be corrected, and support can fix it.

**This replaced an unverified email edit.** `PUT /users/{id}` was changing the email outright from
the profile form, with nothing confirming it — on a platform whose login codes arrive by email,
that let anyone holding a session redirect them. That field is gone from the form.

## 8f. Session 65 — leave hierarchy, team gating, salon profile editing

### Leave: one rung above, or higher

`LeaveApprovalScope` replaced a flat "ops_admin or super_admin" check that was wrong in both
directions — a support LEAD could not approve their own team's day off, and one ops admin COULD
approve another's.

| Whose leave | Who decides |
|---|---|
| support agent, finance, read-only | support lead, ops admin, or the owner |
| support lead | ops admin or the owner |
| ops admin | the owner only |
| anyone | **never themselves**, at any rank |

`GET /api/v1/admin/team/leave/pending` is filtered with the SAME predicate that guards the
decision, so nobody is shown a row whose buttons would 403.

### The Team screen was showing controls that always failed

`TeamController` was already correct — `updateMember` is ops-only, `setAvailability` self-checks,
`PUT /queues/{tier}` is ops-only. The **console** showed Edit and Pause on every row to everyone,
so a support agent clicking either got a 403. Now: your own Pause, and nothing else, unless you are
ops. The Queues tab is hidden from anyone who cannot even read it, and read-only for a lead.

### Salon profile editing — new, nothing existed before

| Endpoint | Guard | Notes |
|---|---|---|
| `PATCH /api/v1/salons/internal/{id}/profile` | `hasRole('SERVICE')` | bmp-salon still rejects `status` |
| `PATCH /api/v1/admin/salons/{id}/profile` | authenticated staff, then `SalonEditScope` | per-FIELD, reason required |

`SalonEditScope` is per-field rather than per-record, unlike the other scope classes:

- **support agent / lead** — `bookingNotifyEmail`, `bookingNotifyPhone`
- **ops admin / owner** — those plus `name`, `area`, `pincode`, `address`, `about`, `categories`

**Nobody, through this path:** `status` (moderation owns it, with its own audit trail) or
`location` (the owner sets the map pin from the shop; nobody in an office has that evidence).

A request containing a field the caller may not change is **rejected whole**, naming the refused
fields. A partial save reported as success is the worst outcome: the agent believes the address is
fixed, the customer still cannot find the shop, and nothing says the two disagree.

### 401 vs 403 on the console

`AdminSecurityConfig` had no `AuthenticationEntryPoint`, so Spring Security 6 answered an
*unauthenticated* request with **403**. A super admin whose token had expired saw "Request failed
with status code 403" on every panel while the shell still looked signed in — the console only
treats 401 as "session over". Now 401 with `SESSION_EXPIRED` when there is no session; 403 only
when there is one and it is not enough.

## 9. Re-running the scan

After changing any controller:

```powershell
mvn -q -pl bmp-common test
```

`WriteEndpointAuthTest` fails and names any POST/PUT/PATCH/DELETE with no `@PreAuthorize`;
`PublicPathsTest` fails if a service stopped declaring `public-paths`, or set it to `/**`.

> **Session 43:** these were two `python3` heredocs pasted into this document — you had to know
> they existed, find them, and paste them into a shell. They are JUnit tests now, so they run on
> every build whether or not anyone remembers this section. **A check you have to remember to run
> is a check that stops being run**, and the whole point of this file is the invariant, not the
> ritual.

A GET without `@PreAuthorize` is not automatically a bug — browsing must work before sign-in, and
bmp-booking enforces ownership in the method body. But **every unprotected endpoint needs a
reason, and the reason belongs in this file.** If you can't write the reason, that's the finding.

To see the full picture rather than just the failures — every endpoint with the rule that guards
it — read the tables in §§2–8 above, which are maintained by hand precisely so that the *reason*
sits next to the rule.
