# Pending work — measured, not remembered

Produced by scanning all three repos: every frontend API call cross-checked against every
backend route, every backend endpoint checked for a consumer, and every `TODO`/`FIXME` read.

**153 backend endpoints. 27 have no frontend consumer. 1 frontend screen has no backend call at
all — and it's the one that matters.**

Ordered by consequence, not by effort.

---

## 0. The headline

> ✅ **FIXED in Session 28.** Left here because the shape of the bug is worth remembering.
>
> **Booking creation was not wired.** `ConfirmScreen.tsx` line 157:
> `onPress={() => setDone(true)}`
>
> The button flipped a local boolean and showed a success screen. No API call. No booking. The
> backend endpoint `POST /api/v1/bookings` had existed since Phase 1 and was never called.

A customer could browse, pick a salon, service, stylist and slot, apply a coupon, tap **Confirm
booking**, see "Booking confirmed" — and nothing whatsoever was recorded. The salon would not
know they were coming.

**The file's own header comment claimed it created a booking "against bmp-booking's API when
mocks are off".** It did not. That is the lesson worth keeping: a comment describing intent
reads exactly like a comment describing behaviour, and only one of them is checked by anything.
When auditing, trust the call sites, not the prose.

Now wired, with: no automatic retry (a repeated POST after a lost response double-books a
slot), double-submit guards, error messages that differ by failure so the customer isn't told
to retry the one thing that cannot work, the real `bookingRef` on the success card, and an
honest `PENDING` state instead of a promised confirmation email nobody sends.

Still outstanding on this path: **server-side idempotency**. The client sends an
`Idempotency-Key` header that bmp-booking ignores. Until it is honoured, a lost response is
still a possible double booking.

---

## 0. Security — two open doors found in Session 41

> Both were **write endpoints with no authorization**, and both survived the Session 29 sweep.

| # | What | Status |
|---|---|---|
| ~~X1~~ | ~~`PUT /salons/{id}` — no `@PreAuthorize`, accepted `status`~~ | ✅ **FIXED.** Any logged-in user could approve their own salon (skipping moderation) and rename or relocate anyone else's. Now owner-scoped; `status` returns 403. |
| ~~X2~~ | ~~`PUT /reviews/{id}` — reachable with **no credential at all**~~ | ✅ **FIXED.** `public-paths` had `/api/v1/reviews/*` for the public GET. **Those patterns are path-only and method-blind** — a read that should be public silently published the write. Anyone on the internet could rewrite any review. |
| ~~X3~~ | ~~Salon replies could be posted by anyone~~ | ✅ **FIXED.** Login was the only gate, so any customer could post a public reply attributed to any salon — invisible to the salon, and credible to the customer. |
| ~~X4~~ | ~~`review` had no author~~ | ✅ **FIXED (V004).** Without it, `hasRole('CUSTOMER')` still meant *any* customer edits *anyone's* review — and it would have looked protected. |
| ~~X5~~ | ~~Nothing would catch the next one~~ | ✅ **CI job: every write endpoint has `@PreAuthorize`.** Verified green. Exemptions are credential-establishing endpoints only, each annotated with what actually gates it. |
| **X6** | **The Session 29 sweep's blind spot is worth remembering** | It looked for endpoints with *no public path*. X2 *looked* deliberately covered by one. A sweep is only as good as the question it asks — which is why X5 is a build step, not another sweep. |

---

## 0a. The API contract gap — Sessions 38 & 40

> **The customer discovery journey had never worked against a real backend.**
>
> Four Zod schemas asked for fields the server never sent. `NearbySalonResponse` was
> `(id, name, distanceKm)`; `SalonSchema` demanded eight more, all required. The parse throws on
> the first real response — so browse → salon page → pick stylist → pick slot all failed. Only
> "Confirm" worked.

**The cause is worth more than the fix.** `USE_MOCKS` defaults ON and the mocks were written from
the *client's assumptions*, not the *server's contract*. A mock that never has to agree with the
server guarantees the two diverge and hides it while they do. Session 38 found the identical root
cause in `getSlots` and it was treated as isolated.

| # | What | Status |
|---|---|---|
| ~~A1~~ | ~~`getSlots` parsed the wrong shape~~ | ✅ Session 38 |
| ~~A2~~ | ~~4 discovery schemas vs. columns that didn't exist~~ | ✅ Session 40 — V011 + rewritten schemas |
| ~~A3~~ | ~~No way to detect a third instance~~ | ✅ `ApiContractTest` (bmp-common). **36 schemas match.** Found 4 more on its first run. |
| ~~A4~~ | ~~The guard is a local script, not CI~~ | ✅ Session 43 — it's `ApiContractTest` in bmp-common, so it runs under `mvn verify` locally and in your IDE. In CI it **skips** (assumption, not failure) because the workflow clones one repo; failing a build over a missing sibling repo teaches people to ignore the failure. **Run it before touching any API shape.** |
| **A5** | **Mocks are still hand-written** | They now speak the server's shape, but nothing enforces it. Fixture tests captured from real responses (T3) would. |
| **A6** | **No type checking in the guard** | It compares field NAMES only. A schema saying `number` where the server sends `String` still passes. |

**New in Session 43 — the auth surface.** Login/signup was swept end to end across all four roles.
Fixed: a fail-open `lookupUserByPhone` that turned any bmp-user outage into a *silent duplicate
signup*; the same pattern in `resolveSalonScope`, which minted owner tokens with `salonId = null`
so the console 403'd everywhere; OTPs replayable for their full 5-minute TTL (V005 `consumed_at`);
`000000` unlocking **every** account, now allowlisted to the six seeded phones; a phone validator
that accepted `+91919876500003`; a staff sign-in door that could mint a customer account from a
typo. See BMP/CONTEXT.md Session 43 and BMP-FE/CONTEXT.md Session 43.

| # | Still open after that sweep | Note |
|---|---|---|
| **N11** | **No rate limit on `/otp/request` beyond the 55s per-phone cooldown** | One phone is throttled; ten thousand phones are not. Email is real delivery now, so this is a route to burning the SMTP quota — and to using BMP as a spam relay. IP-level throttling at the gateway. |
| **N12** | **`otp_requests` rows are never cleaned up** | Every code ever requested is kept forever, each holding a phone number and an email. A DPDP retention problem that grows monotonically. A daily job deleting consumed/expired rows older than N days. |
| **N13** | **`bmp-admin` staff login is untouched by the Session 43 sweep** | Separate door — email + password + TOTP against bmp-admin, and `VITE_USE_MOCKS` defaults ON, so the console can look healthy while the backend is down. Nobody has audited it since Session 28. |

---

## 0b. Tests — started in Session 39

**46 tests, 4 files, all pure logic.** CI runs them on every push; `-DskipTests` is gone.

| File | Tests | Protects |
|---|---:|---|
| `CancellationTermsTest` | 18 | What a customer is charged, from terms frozen months earlier |
| `BookingStatusTest` | 14 | Which moves are possible — mostly the impossible ones |
| `MoneyTest` | 9 | The half-up rounding rule that multiplies everyone's income |
| `MaskPhoneTest` | 5 | The only thing between the salon desk and a customer list |

**What is NOT covered, honestly:**

| # | Gap | Note |
|---|---|---|
| **T1** | **No controller or service tests** | Every `@PreAuthorize` rule, every `requireSameSalon` check and every authorization boundary is still unverified. `API_ACCESS.md` documents 170+ endpoints; nothing tests that the annotations match the document. Highest-value next test target by a distance — the security work has been done four times and checked by reading. |
| **T2** | **Context-load tests are excluded, not fixed** | `*ApplicationTests` need PostgreSQL/Kafka/Eureka. Excluded in the root pom with the reason inline. **Testcontainers** would let them run for real; delete the exclusion then rather than adding a profile. A context-load test that never runs is one that has been quietly failing for months. |
| **T3** | **No frontend tests in either repo** | BMP-FE and BMP-ADMIN have `tsc` and lint in CI and nothing else. The Zod boundary schemas are the obvious target — Session 38 found `getSlots` parsing a shape the server never sends, which a single fixture test would have caught years earlier than production would. |
| **T4** | **`requireNoSelfOverlap` untested** | Private in a Spring service. Worth extracting to a static helper *when there's a reason beyond testability* — restructuring code purely to test it is its own kind of debt. |

---

## 1. Blockers — the product does not do its job

| # | What | Where | Note |
|---|---|---|---|
| ~~B1~~ | ~~**Booking creation not wired**~~ | `ConfirmScreen.tsx` | ✅ **FIXED, Session 28.** `createBooking()` in `api/bookings.ts`, wired with no auto-retry, double-submit guards, per-status error messages, and the real `bookingRef` on the success card. |
| B2 | **No payments** | `bmp-payment/.../PaymentOrderService.java:55` | The service exists as a *data model only* — 10 entities, repositories, CRUD. The comment at line 55 reads `TODO(Phase 3 / BMP-19): real Razorpay create-order API call goes HERE`. No SDK, no webhook handler, no signature verification. |

B2 is why every booking sits `PENDING` forever, which is why manager check-in is unreachable,
refunds land in `blocked`, and referral payouts are unimplemented. One gap, four symptoms.

---

## 2. Security — must land before anything is deployed

| # | What | Detail |
|---|---|---|
| S1 | **52 endpoints need no credential** | `bmp-salon` 35 of 51, `bmp-notification` 7, `bmp-review` 6, `bmp-payment` 4. Cause: `CommonSecurityConfig` defaults `bmp.security.public-paths` to `/**`, so a service that never sets it authenticates nothing. **Omission fails open.** |
| S2 | **Anyone can mark a payment captured** | `PUT /api/v1/payment-orders/{id}/status` → `updateStatusDevOnly`. No `@PreAuthorize`, in a service with no `public-paths`. Today it is unreachable because nothing is deployed. On a public VM it is a way to confirm bookings without paying. |
| S3 | **Reviews are unverified** | `ReviewService.java:37` — `TODO(Phase 3 / inter-service): call bmp-booking-service (Feign) to confirm`. Nothing checks the reviewer ever had a booking. Anyone can review any salon, any number of times. On a marketplace whose entire value is trustworthy ratings, this is not a small bug. |

S1 and S2 are the same root cause and should be fixed together — see the Track H section of
`project-management/BMP_MVP_Fundraise_Plan.xlsx`.

---

## 3. Correctness — quietly wrong rather than broken

| # | What | Where |
|---|---|---|
| ~~C1~~ | ~~**Cancelling a booking does not release the coupon**~~ | ❌ **THIS WAS NEVER TRUE.** `BookingService.cancel` has called `rewards.release(...)` since Session 22. I reported it from a `TODO` in `CouponRedemptionService:138` that had been stale for eight sessions. The comment is now corrected. **Lesson, twice over: this audit warned "trust call sites, not prose" about `ConfirmScreen`, then made exactly that mistake itself.** |
| ~~C2~~ | ~~**Booking trusts the client for price and duration**~~ | ✅ **FIXED, Session 30.** `BookingService.create` now fetches the salon's menu over Feign and uses its name, price and duration; the request's values are ignored. The duration half was the more dangerous one — see below. |
| ~~C3~~ | ~~**Commission and policy snapshot are placeholders**~~ | ✅ **FIXED, Session 30.** `salon_policy.commission_bps` added (V009, additive, default 1200 = today's behaviour). Booking now reads the salon's rate and **freezes the real cancellation terms** into `policy_snapshot`, which was the literal string `"{}"` on a column documented as "FROZEN copy of salon_policy, never changes". Owners can see their rate but cannot set it — null means "leave unchanged", so a client omitting the field can't silently reset a negotiated rate. |
| C4 | **Nothing enqueues salons for moderation** | `POST /admin/salons/{id}/enqueue-review` has no caller. The approval queue stays permanently empty, so the moderation gate looks healthy while doing nothing. |
| C5 | **`near()` is in-memory Haversine over every salon** | `SalonService.java:24`. Fine at 10 salons; not at 1,000. Flagged, not urgent. |

---

## 4. Built with no UI — backend done, frontend absent

These are finished endpoints nobody can reach. Cheap wins if any of them matter; otherwise
honest candidates for deletion.

| Area | Endpoints | Verdict |
|---|---|---|
| **Wallet & referrals** | 5 (`/referrals/my-code`, `/users/{id}/wallet`, wallet transactions, admin credit, referral-code) | Referral is a real growth lever and there is no way to see or share a code. Blocked on payments for payout, but the *code* could ship now. |
| ~~Salon booking history~~ | ~~`GET /bookings/salon`~~ | ✅ **WIRED, Session 35.** Written, secured and paginated in Session 16, then left orphaned for nineteen sessions — an owner could see today and had no way to look up last Tuesday. `HistoryPanel` now serves it on both the owner and manager desks. *A backend endpoint with no caller is not half-finished work; it is invisible work.* |
| ~~Customer-at-salon view~~ | ~~`GET /bookings/salon/customer/{id}`~~ | ✅ **BUILT, Session 36.** Didn't exist at all — `BookingRepository` had `findByCustomerId` and `findBySalonId` and no combination, so the returning-customer view was unbuildable. Tap a name in History. |
| **Salon combos** | 5 (full CRUD) | Package deals. No UI anywhere. Decide: build the UI or delete the feature. |
| **Reviews** | Whole service | No customer-facing review UI at all. Blocked behind S3 anyway. |
| **Onboarding state** | 4 (`/users/{id}/onboarding-state`) | Built for a first-run flow that was never designed. |
| **Support tickets (old CRUD)** | 4 (`/api/v1/support-tickets/**`) | Superseded by `SupportDeskController`, which the console actually uses. This is the controller that was outside the admin security matcher. **Strong candidate for deletion** — a second, unused way into ticket data is pure liability. |
| **Data-request identity verification** | 1 | DPDP erasure requires it; the console has no button. |

---

## 5. Frontend gaps and placeholders

| # | What | Where |
|---|---|---|
| F1 | **Contact form goes nowhere** | `ContactForm.tsx:65` — `TODO(backend): POST to a real /api/v1/contact endpoint`. It shows a success message. There is no endpoint and nobody receives anything. Same class of bug as B1, lower stakes. |
| ~~F2~~ | ~~**Salon signup drops `type` and `address`**~~ | ✅ **FIXED, Session 40.** Collected since Session 15 and discarded because `CreateSalonRequest` had nowhere to put it — the owner typed into a field that went nowhere. V011 added the columns; `type` maps onto `categories`. **The FE signup form still needs wiring to send them.** |
| F3 | **Coordinates are not geocoded** | `SalonSignupSheet.tsx:166` — every salon created through signup gets placeholder coordinates, so proximity search will rank them wrongly. |
| F4 | **Invites are shown, never sent** | `staff.ts:90`, `InviteManagerPanel.tsx:11` — the code is displayed for the owner to relay by hand. WhatsApp/SMS needs DLT registration. |
| F5 | **Ticket replies are recorded, never emailed** | `SupportDeskController.java:126`. Support believes the customer was told. |
| F6 | **Salon rejection is never communicated** | `SalonModerationService.java:127` — an owner whose salon is rejected is not told, and there is no screen where they'd see it. |
| F7 | **Pre-launch placeholders** | `[[REGISTERED_ENTITY_NAME]]`, `[[REGISTERED_ADDRESS]]`, `+91 80 0000 0000`, invented team members, unconfirmed inboxes. All marked `TODO(pre-launch)` in `about/content.ts` and `contact/content.ts`. Blocked on incorporation. |
| F8 | **DPDP erasure deactivates, doesn't anonymise** | `DataRequestService.java:127` — needs `POST /internal/users/{id}/anonymise` in bmp-user, which doesn't exist. A legal obligation with a partial implementation is worse than a documented gap. |

---

## 5c. Cancel / reschedule — Session 37

| # | What | Status |
|---|---|---|
| ~~R1~~ | ~~**`free_cancel_hours` was frozen and read by nothing**~~ | ✅ **FIXED.** Since V002, frozen since Session 30, never opened. Two minutes' notice and three weeks' produced identical outcomes. `CancellationTerms` now reads the snapshot and writes the decision. |
| ~~R2~~ | ~~**The salon could not cancel**~~ | ✅ **FIXED.** `Transition(CANCELLED, Actor.SALON)`. Always fee-free, reason required. |
| ~~R3~~ | ~~**Reschedule didn't exist**~~ | ✅ **BUILT.** `booking_modification` had zero rows since V002 and its javadoc named columns that don't exist. Both actors, availability-validated, `booking_modification` finally written to. |
| ~~R4~~ | ~~**No upcoming view**~~ | ✅ **BUILT.** `GET /bookings/salon/upcoming`, ordered by appointment time. |
| ~~R5~~ | ~~**Reschedule UI**~~ | ✅ **BUILT, Session 38.** `RescheduleSheet` — one component, both actors. Real slots from the availability algorithm, one picker per service, `excludeBookingId` set. Customer entry is gated on `reschedule-eligibility`; salon entry on the salon's `salonCanRescheduleDirectly` policy (off by default), and when off the row names the alternatives instead of just refusing. |
| ~~R6~~ | ~~**Same-booking self-overlap**~~ | ✅ **FIXED, Session 38.** `requireNoSelfOverlap` — checked after the availability pass, before anything is written. Only refuses **same stylist**: two services in parallel with different stylists is a real thing salons do (a manicure while a colour develops) and refusing all overlap would break it. |
| ~~R9~~ | ~~**`getSlots` never worked against a real backend**~~ | ✅ **FIXED, Session 38.** It parsed the response as `SlotGroup[]` (`{label, slots[]}`); bmp-salon returns a flat `SlotResponse(start, end, stylistId)`. A Zod parse of the real payload throws — **the booking flow's slot picker would have failed on its first live request.** Hidden because `USE_MOCKS` defaults ON and `mockSlots` returned the shape the client wanted, so client and server never had to agree. Grouping now happens client-side where it belongs, and `stylistId` (previously discarded) is kept — it's the only way to know which stylist an "any available" slot belongs to. |
| **R7** | **Refund amounts** | Blocked on **B2**. The *decision* is recorded now (band, bps, paise, reason); bmp-payment will read those columns rather than re-deriving them, because the terms that count are the ones frozen at booking time. |
| **R8** | **Reschedule doesn't re-check the coupon** | By design, worth watching. Price, duration, commission, coupon and `policy_snapshot` all stay as they were — the customer is moving an appointment, not rebuying it. If a coupon is ever made date-restricted, revisit. |

---

## 5b. Notifications — closed in Session 34, and what's left

| # | What | Status |
|---|---|---|
| ~~N1~~ | ~~**bmp-booking published no events at all**~~ | ✅ **FIXED, Session 34.** A customer could book an appointment and receive nothing — no confirmation, no reminder, no notice of their own cancellation. The only message BMP had ever sent them was their login OTP. `booking.created` / `.cancelled` / `.completed` now go through the outbox that had been sitting there, wired but unused, since Session 3. |
| ~~N2~~ | ~~**The salon desk showed a bare UUID for the customer**~~ | ✅ **FIXED, Session 34.** V006 snapshots name/phone/email onto the booking; the desk shows the name and a masked number. |
| **N3** | **Reminders** — T-24h and T-2h | **NOT DONE. The highest-value item left in this file.** No-shows are the salon's single biggest complaint about every booking platform, and a reminder is the cheapest thing that reduces them. Needs a scheduled job over `booking_service_item.service_start`, and an idempotency marker so a restart doesn't send twice. |
| ~~N4~~ | ~~**Reveal-phone, audited**~~ | ✅ **DONE, Session 35.** `POST /bookings/{id}/reveal-contact` — reason required, salon-scoped, `SALON_OWNER`/`MANAGER` only. The record goes to `booking_events`, **which the customer can read in their own app** — an audit trail only the platform can see protects the platform; one the data subject can see protects them. No rate limit yet (see N4b). |
| **N4b** | **Rate-limit reveals** | **NOT DONE.** A determined salon could walk their own history revealing every number. Bounded to their own customers, logged per booking, and visible to each of them — but not prevented. A cap (≈20/day, then a support conversation) belongs here once there's real traffic to calibrate against; inventing the number now produces a limit that's wrong in both directions. |
| **N5** | **Templated salon→customer messages** | **NOT DONE.** "Running 20 minutes late" / "your stylist is unavailable" as buttons: BMP sends it, the salon never sees the number. Should become the primary path, with N4 as the escape hatch. |
| **N10** | **Customer sheet from the day view** | **NOT DONE.** History rows open the customer sheet; the booking detail panel on the Today tab does not, because `BookingDetail` has no `salonId` and threading it through was more change than the win justified this session. "Have they no-showed before?" is a question asked while the customer is at the door, so this is worth doing. |
| **N9** | **Masked calling (in-app)** | **NOT DONE.** The end state: BMP bridges the call and neither party sees the other's number, as delivery apps do. Needs a telephony provider (Exotel/Knowlarity/Twilio) and a registered company — blocked on Track 0, not on code. `revealCustomerContact` is the seam: it becomes "place a bridged call", the number stops being returned, and the reason + audit + customer visibility are unchanged. That's why the reason is collected server-side on a POST rather than logged client-side. |
| **N6** | **`booking.confirmed`** | Blocked on B2. Bookings sit in `PENDING` until the Razorpay webhook, so today's message says "requested", not "confirmed" — deliberately, since telling someone their appointment is secured before payment is a promise the platform hasn't made. |
| **N7** | **SMS is still a log line** | `LoggingSmsSender` is the only `SmsSender`. Blocked on DLT registration, which needs the company to exist (Track 0). Email sends for real today. Swapping in a real gateway is one `@Service` bean — that is what the interface was for. |
| **N8** | **Review prompt on completion** | Deliberately not sent. bmp-review accepts a review from anyone for anything (S3) — mailing "rate your visit" links before attendance is verified would invite exactly the fake reviews the check prevents, at scale, with BMP's name on the invitation. `booking.completed` is what it will hang off. |

---

## 6. Suggested order

Nothing here reorders the fundraise plan; it fills in the detail.

1. ~~**B1 — wire the booking button.**~~ ✅ Session 28.
2. ~~**S1 + S2.**~~ ✅ Session 29 — see `API_ACCESS.md`.
3. ~~**C1, C2, C3.**~~ ✅ Sessions 29–30. C1 turned out never to have been broken.
4. **B2 — Razorpay.** ~13 days, unblocks four other things. **This is now the top of the list**,
   and it is blocked on Track 0: the account and KYC have to exist first.
5. **S3** — before any real customer leaves a review, i.e. before launch, not before demo.
6. **F1** — an unanswered contact form on a live site is worse than no form.
7. Decide on §4: build the UI or delete the endpoints. Leaving them is the worst option — dead
   code that looks alive misleads whoever reads the codebase next, which by then may not be you.

---

## 5d. Session 44 — owed, and why each is deferred rather than done

**~~Real image upload (object storage). Now in THREE places.~~ ✅ DONE — Session 44, V015.**
All three surfaces (`salon.imageUrl`, `salonService.imageUrl`, the `salon_photo` gallery) now
accept a real JPG/PNG/WebP upload as well as a pasted link. MinIO in docker-compose; because it
speaks the S3 API, moving to S3/R2/Spaces is the six `bmp.storage.*` values in bmp-salon's
application.yml and no Java at all.

Decisions worth knowing about:
· **Upload goes THROUGH bmp-salon, not via a presigned URL.** A presigned PUT cannot be
  validated — whatever the client sends is what lands in the bucket. At 8MB and twelve photos per
  salon the bandwidth is affordable and the ability to reject is not optional.
· **The server re-encodes every image**, which is what strips EXIF. Phone cameras write GPS
  coordinates into photos; a stylist shooting their work would otherwise publish the salon's exact
  location, and a photo taken at home would publish their home address.
· **`storage_key` records ownership**, so deleting a row deletes our file and never touches an
  image the salon hosts elsewhere. Replacing an image deletes the superseded object.
· Object deletion is deliberately non-fatal, so a storage outage during a delete leaks an object
  by design. V015 adds partial indexes that make reconciliation a cheap bucket-diff. **A periodic
  orphan sweep is still unwritten** — the only piece of this feature not done.

**Per-stylist services are inert.**
The staff editor lets you attach services to a stylist, and the availability algorithm does not
read that link — any stylist can be assigned to any service. So the control saves, and changes
nothing. That is the "control that always fails reads as broken software" shape, one step worse:
it *succeeds* and still changes nothing, which is the kind of bug you only find by testing the
outcome rather than the click. Either wire it into `AvailabilityService` or remove the UI.

**Converge the internal-endpoint namespace.**
bmp-rewards serves its service-to-service endpoints at `/api/v1/internal/**`, which has no gateway
route — genuinely unreachable from outside. Every other service nests them under the public parent
(`/api/v1/salons/internal`, `/api/v1/bookings/internal`), which the parent's `/**` predicate
matches, so **those are reachable from the public internet** and depend entirely on
`@PreAuthorize("hasRole('SERVICE')")`.

All six are guarded today, and `GatewayRouteTest` now asserts that on every build, so this is not
an open hole. But bmp-rewards' arrangement is strictly better: it puts a network layer underneath
the authorization one, so a future mistake in a single annotation is not immediately
internet-facing. Deferred because moving a path prefix breaks every Feign client that calls it —
a mechanical change that deserves its own commit and its own review, not a footnote in a UI pass.

**Closed in Session 44:** the missing-gateway-route bug class. `GatewayRouteTest` asserts every
controller `@RequestMapping` prefix is covered by a route predicate, using a *segment-aware*
match — a plain `startsWith` would have reported `/api/v1/coupons` as covering
`/api/v1/coupon-requests`, reintroducing the exact bug it exists to catch.

---

## 5e. Session 45 — support, and the location bugs found on the way

**Closed: nobody could open a support ticket.** The headline finding of the session. bmp-admin
had a complete support desk since Session 21 — ticket/message tables, SLA clocks, canned
responses, triage, five console pages — and `SupportTicketController` was `ROLE_SERVICE` with
**no callers anywhere in any of the three repos**. A salon owner whose payout looked short, or a
customer disputing a cancellation fee, had no route to a human at all. A call centre with the
phone line unplugged.

Now: `/api/v1/support` in bmp-user (all roles), a Help tab on owner/manager/customer, "Get help"
on a booking row, and "Log a call" in the console for phone-ins. Identity is derived from the JWT
at the bmp-user boundary and re-checked against the ticket in bmp-admin — the calling service is
trusted to authenticate, never to authorise.

**Closed on the way: two location bugs that were costing salons bookings.**

· `listSalons` sent a hardcoded `'12.9716,77.5946'` — Bengaluru city centre — as every customer's
  position. A customer in Whitefield saw salons 18km away and none near them, and nothing on
  screen admitted the location was invented, so the honest reading was "there are no salons near
  me". Now uses the device position, with an explicit, retryable notice when permission is denied.
· Search never touched service names, and ran client-side over the already-fetched list. So
  "balayage" — the most natural thing a customer types — matched nothing, and a salon outside the
  radius was unfindable. Now server-side across name, area, address and live service names.
· Nothing could set a salon's coordinates. Signup used the AREA CENTROID, so every salon in
  Indiranagar sat on one point, and `SalonResponse.location` was returned by the server but
  dropped by the frontend schema — so no screen could ever show an owner where their salon was.
  `LocationField` now takes one GPS tap or a pasted Maps link.

### Found in the Session 45 self-review (4 bugs, all fixed)

Asked "is it done, no bugs?", I went looking rather than answering. Recorded because the shapes
recur:

1. **A manager could read the owner's support tickets.** Salon scope was passed for any
   salon-scoped role, which reads as "support is a salon-level concern" — right until you consider
   that "I need to revoke my manager's access, money has gone missing" is an ordinary
   `account_issue` ticket. Now OWNER-only for reads; writes still stamp `salonId` so the owner
   sees what managers raised. *Writing a scope and reading by it are different questions.*
2. **Customers were offered salon-only categories** ("My salon isn't showing up", "Payments and
   payouts"). The filter was `audience === 'salon' ? forSalon : true` — a customer picking one
   files into the partner queue and gets mis-triaged. Two independent flags now.
3. **`requesterEmailMasked` / `requesterPhoneMasked` were never masked.** Raw email and phone
   passed into fields whose names promise masking, readable by every staff role that can list the
   queue including READ_ONLY. No masking helper existed anywhere in bmp-admin. `ConsoleDtos` says
   it outright: *"Masking is only meaningful if the unmasked value never leaves the server."*
   Pre-existing, but newly load-bearing now that in-app tickets exist.
4. **In-app tickets were anonymous to agents.** `requesterName` is hardcoded null (older TODO) and
   the contact columns are only populated for account-less phone-ins — so every ticket raised
   through the app showed no requester at all. `raisedById` is now on the response.

**5th bug, found when asked "is location done?":** the request was *"manager or owner can update
the location"* and only the OWNER could. `LocationField` went into the Profile tab, which is
owner-only — correctly, since `PUT /salons/{id}` can also rename the business. So the manager,
who is the person most likely to be standing in the salon with a phone, had to ask the owner to
set the pin from a laptop somewhere else.

Fixed with a dedicated `PUT /salons/{id}/location` (owner OR manager, same shape as the photo
endpoints) and a shared `SalonLocationPanel` on a Location tab of both dashboards. The copy in the
Profile tab was REMOVED rather than left alongside — two code paths writing one value is exactly
how a field like this ends up subtly wrong, and this is the field least able to survive that.

### Still open

**`nextTicketRef` is not concurrency-safe.** `create` counts existing rows and adds one — two
simultaneous tickets can collide on `uk_support_ticket_ref`. Flagged since Session 21 as
"replace with a DB sequence before go-live", and **materially more likely to bite now** that real
users can create tickets rather than only a service that never called it. Needs a Postgres
sequence, which is a migration plus a decision about back-filling.

**Staff replies are recorded but never emailed.** `SupportDeskController.reply` carries a TODO
saying an agent believing they replied when the customer heard nothing is worse than no reply
feature at all. Session 45 partly fixes this — the user can now READ the reply in their Help tab —
but nothing notifies them it arrived, so they only see it if they think to look. Wiring
`ticket.replied` through the existing outbox → Kafka → NotificationDispatcher path is the
remaining piece and reuses machinery that already exists for booking events.

**Wallet and referrals have no UI.** Four live endpoints in bmp-rewards (`/wallet`,
`/wallet/transactions`, `/referral-code`, admin credit) with zero callers in either frontend.
Worth checking whether a balance can move at all before building a screen that always reads zero —
no money has ever passed through BMP.

**Discovery is still an in-memory Haversine over every salon.** Fine at current scale. The
`q` filter added this session runs in the same pass, so it inherits the same ceiling.

---

## 5f. Session 46 — salon onboarding: pending / rejected / approved

**Closed: an owner was never told their own status.** The largest remaining
failure-that-looks-like-success in the product. `OwnerDashboard` rendered a full working desk
whether the salon was pending, rejected, suspended or approved — the login response carried no
status and no screen asked. A rejected owner would build a service menu, invite managers, set
opening hours, and wait for bookings that could never arrive, because customers cannot see an
unapproved salon.

Now: `GET /salons/{id}/approval` (owner or manager) and a `SalonApprovalGate` that routes by
status. PENDING is told and then let through — services, staff, hours and photos are genuinely
useful preparation, and the approval is worth more on the day it lands if that work is done.
REJECTED and SUSPENDED lead with the fix instead, since there is nothing useful to prepare for a
salon that won't go live as things stand.

**Closed: approve/reject was completely silent.** `SalonModerationService.decide` published
nothing. A moderator approved a salon and the owner found out by opening the app and guessing; a
rejection they found out never — which turns the single most anticipated moment in a partner's
relationship with BMP into a silence indistinguishable from being ignored. Now
`salon.status.changed` flows through the existing outbox → Kafka → NotificationDispatcher path.
Email is live (JavaMailSender, same as OTP); SMS/WhatsApp remain the "configuration pending"
stubs from Session 43. SMS carries approvals only — a truncated refusal is worse than no message,
because the owner then knows they were refused and still not why.

**Closed: rejection was a dead end.** V007 drops `uk_salon_review_salon` so a salon has ONE ROW
PER SUBMISSION. `decide`'s guard — *"re-approving a rejection would erase the fact it was ever
rejected"* — is exactly right and stays untouched; reusing the row would have meant relaxing it.
Resubmission creates a new row instead, and `enqueue`'s idempotency is now expressed properly as
"no PENDING row exists", which absorbs bmp-salon's retries while allowing the legitimate second
submission the unique index used to forbid.

**Where the event is published, and why it matters.** In bmp-salon's `InternalSalonController`,
not bmp-admin. Two reasons: bmp-admin's `SalonDto` has no owner field at all, so it literally
cannot address the message; and publishing from bmp-salon puts the outbox write in the SAME
transaction as the status change, so "the salon is approved" and "the owner was told" cannot
disagree.

**Closed: signup used the AREA CENTROID and collected no photos.** The map pin is now a REQUIRED
signup step (GPS tap or Maps link), replacing `lat: areaMeta?.lat ?? 12.9716` and the TODO that
had been asking for exactly this since Session 15. Required rather than optional because signup is
the one moment the owner is definitely engaged — "add it later from the dashboard" means most
never do.

Photos are OPTIONAL and offered AFTER creation, which is forced by the API as much as chosen: the
upload endpoint is scoped to `salons/{salonId}/media`, so no salon exists to upload against until
the salon is created. An owner without a good photo to hand shouldn't be turned away, but they are
told it affects approval.

**Closed: moderators approved blind.** The review panel showed a name, an id and an area — nothing
a customer looks at — while the decision checklist asks the reviewer to confirm "photos genuine".
They were being asked to certify something the screen never showed them. `SalonPreview` now renders
the gallery, and an EMPTY gallery is stated explicitly rather than left blank, because "this salon
has no photos" is itself a signal worth having before approving. Resubmissions carry a
"Submission N" badge and the owner's own note about what they fixed, so a second look is a
re-check rather than a fresh review.

### The first real compile error — and what it says about the verification gap

`SalonService.update` took `UUID id`, not `UUID salonId`. Session 44's cover-image work wrote
`requireOwnKeyOrNull(salonId, ...)` there, copying the shape used in `addPhoto`, `updatePhoto`,
`addService` and `updateService` — where the parameter genuinely IS `salonId`. Four correct call
sites and one wrong one, in the same file.

**Tree-sitter could never have caught this.** It is syntactically perfect Java; the identifier is
simply not in scope. Every "386 files parse clean" claim in this document was true and did not
mean what it might have looked like it meant.

A scope checker now exists for this class of bug (bare identifiers passed as call arguments that
aren't declared in the enclosing method, its class fields, or any enclosing binding form). Run
across the 15 files changed in Sessions 44–46 it reports **zero** others. That is a real result but
a narrow one: it checks name resolution for one syntactic shape, not types, not method existence,
not overload resolution.

### The second compile error — a global find/replace that hit three records

`SalonDetailResponse` gained an `imageStorageKey` component it was never meant to have, so
`detail()` passed 16 arguments to a 17-component record. IntelliJ reported it 30 times.

Cause: the Session 44 edit used Python's `str.replace()`, which replaces EVERY occurrence. The
line `String area, String address, String about, String imageUrl,` appears in three records, so
one intended edit became three:

| record | intended? | consequence |
|---|---|---|
| `UpdateSalonRequest` | ✅ yes | correct |
| `CreateSalonRequest` | ❌ no | a field `create()` explicitly ignores (`setImageStorageKey(null)`) — accepted and silently discarded |
| `SalonDetailResponse` | ❌ no | **the arity error, and a leak**: this is the PUBLIC customer detail response, so it would have published internal object-storage keys to everyone browsing a salon |

The arity error was the loud symptom; the public leak was the quiet one, and only visible because
the compiler forced a look at that record. Both unintended components removed.

**Two static guards now exist** for the classes of bug tree-sitter cannot see:
`undefined bare identifiers passed as arguments` (caught the `salonId`/`id` scope error) and
`record arity vs constructor call`, file-scoped so same-named records in different modules don't
produce noise. Both report clean across all 386 files.

**Neither replaces `mvn verify`.** They check name resolution and argument counts — not types, not
overload resolution, not generics. Maven Central is still unreachable from this environment (403
at the proxy), so the compiler has still never run here. Both real errors so far were found by
Darshan's IDE, which is the only thing actually type-checking this code.

### Still open after Session 46

**`nextTicketRef` concurrency** (Session 45) — unchanged and still owed a Postgres sequence.

---

## How this was produced

Not from memory. Scripted cross-reference of `api.(get|post|put|...)` calls in
`BMP-FE/src/api/*.ts` and `BMP-ADMIN/src/api/*` against every `@RequestMapping` +
`@(Get|Post|...)Mapping` in `BMP/*/controllers/*.java`, plus a full `TODO`/`FIXME` read.

Re-run it after any significant change — the counts in the header are the fastest way to see
whether the gap is closing or widening.
