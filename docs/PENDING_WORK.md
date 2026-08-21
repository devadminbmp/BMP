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
| ~~A3~~ | ~~No way to detect a third instance~~ | ✅ `scripts/check-api-contracts.py`. **35 schemas match.** Found 4 more on its first run. |
| **A4** | **The guard is a local script, not CI** | The repos are separate; neither workflow has the other checked out, so a CI job can't do this honestly. Cross-repo checkout is the real fix when it's worth the complexity. **Run it before touching any API shape.** |
| **A5** | **Mocks are still hand-written** | They now speak the server's shape, but nothing enforces it. Fixture tests captured from real responses (T3) would. |
| **A6** | **No type checking in the guard** | It compares field NAMES only. A schema saying `number` where the server sends `String` still passes. |

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

## How this was produced

Not from memory. Scripted cross-reference of `api.(get|post|put|...)` calls in
`BMP-FE/src/api/*.ts` and `BMP-ADMIN/src/api/*` against every `@RequestMapping` +
`@(Get|Post|...)Mapping` in `BMP/*/controllers/*.java`, plus a full `TODO`/`FIXME` read.

Re-run it after any significant change — the counts in the header are the fastest way to see
whether the gap is closing or widening.
