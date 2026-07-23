# The Availability Algorithm — How `freeSlots()` / `blockWalkIn()` Work

**Status:** First real implementation, Session 9. Previously `AvailabilityApi` was an
intentional stub (see its own javadoc) — this document explains what now backs it.

**Where the code lives:**
- `bmp-salon/src/main/java/com/bmp/salon/api/AvailabilityApi.java` — the contract. Booking
  consumes only this interface and never touches the tables below directly.
- `bmp-salon/src/main/java/com/bmp/salon/services/AvailabilityService.java` — the
  implementation and all the interval math.
- `bmp-salon/src/main/java/com/bmp/salon/controllers/AvailabilityController.java` — the
  REST surface (`/api/v1/availability/slots`, `/slots/any`, `/walk-in`).
- `bmp-booking/src/main/java/com/bmp/booking/controllers/BookingAvailabilityController.java`
  — a small internal endpoint bmp-salon calls to find out what's already booked.

This document is the plain-English version of what those files do. It assumes no prior
context beyond CONTEXT.md's Module 2/3 schema tables.

---

## 1. The problem in one sentence

Given a stylist, a salon, a date, and how long a service takes, find every start time that
stylist could actually be booked at — accounting for their working hours, their breaks,
their leave, the salon's own opening hours, anything already booked, anyone currently mid-
checkout holding that slot, and any walk-in a receptionist has manually blocked.

## 2. The two services involved, and why

BMP is split into microservices with one schema each. The data needed to answer this
question is split across two of them:

| Lives in | Table | What it tells us |
|---|---|---|
| bmp-salon (`salon_schema`) | `stylist_availability` | The stylist's recurring working hours/breaks, plus one-off exceptions and leave |
| bmp-salon (`salon_schema`) | `walk_in_block` | Manual holds a receptionist added for a walk-in customer |
| bmp-salon (`salon_schema`) | `salon_hours` | The salon's own opening hours per day of week |
| bmp-salon (`salon_schema`) | `salon_policy` | `slot_granularity_minutes` — the booking grid, e.g. every 15 minutes |
| bmp-booking (`booking_schema`) | `booking_service_item` | Real, paid-for bookings already on the calendar |
| bmp-booking (`booking_schema`) | `slot_lock` | A ~5-minute hold while a customer is mid-checkout, before payment confirms |

`AvailabilityApi` lives on the bmp-salon side (it's fundamentally a salon/stylist-schedule
question), but two of the six inputs above live in bmp-booking. So `AvailabilityService`
makes one internal HTTP call to bmp-booking — `GET /api/v1/bookings/internal/busy-windows`
— to fetch those two live. That endpoint is locked to service-to-service calls only (the
same `X-Internal-Service-Key` mechanism used elsewhere in this repo), never reachable with
a normal customer or salon-staff login.

## 3. The algorithm, step by step

For `freeSlots(salonId, stylistId, date, durationMinutes)`:

**Step 1 — Find the salon's operating hours for that day of week.**
Look up `salon_hours` for `salonId` + the day of week `date` falls on. If the salon has no
row for that day (closed), return an empty list immediately — nothing else matters.

**Step 2 — Find the stylist's working windows for that exact date.**
This is the part with the most nuance:
- First check for a full-day **leave** row (`stylist_availability.rule_type = 'leave'`,
  `specific_date = date`, no start/end time). If one exists, the stylist isn't working at
  all that day — return an empty list.
- Otherwise, check for an **exception** row for that exact date with `slot_type = 'working'`.
  If one exists, it **replaces** the stylist's usual weekly pattern for that date only
  (e.g. "normally off Tuesdays, but working this particular Tuesday" or "normally 10–6,
  but only 2–6 this specific day").
- Otherwise, fall back to the **weekly template**: the recurring working-hour rows for
  that day of week (`rule_type = 'weekly_template'`, `slot_type = 'working'`, matching
  `day_of_week`).

If none of the above produces any working windows at all, the stylist simply isn't
scheduled that day — return an empty list.

**Step 3 — Clip the working windows to the salon's hours.**
Whatever windows Step 2 produced, intersect them with the salon's opening hours from
Step 1. **The salon's hours always win** — a stylist can't be bookable before the salon
opens or after it closes, even if their own schedule says otherwise. (This was one of the
open design questions from the original paper exercise — see §5.)

**Step 4 — Subtract everything that makes the stylist busy.**
Four sources, all subtracted from the working windows left after Step 3:
1. Recurring breaks (`weekly_template` rows with `slot_type = 'break'` for that day of week).
2. One-off breaks or partial-day leave for that exact date (`exception` or `leave` rows
   with a start/end time set).
3. Walk-in blocks (`walk_in_block` rows for that stylist on that date).
4. Real bookings and checkout holds, fetched live from bmp-booking (Step 2 of §2 above).

**Step 5 — Slice what's left into a bookable grid.**
Whatever time remains after Step 4 is a set of free windows. Each window is sliced into
candidate start times aligned to `slot_granularity_minutes` (default 15 — e.g. 10:00,
10:15, 10:30…). A candidate start time is kept only if there's an unbroken free run of at
least `durationMinutes` starting there. The result is the final list of bookable
`(start, end, stylistId)` slots.

`freeSlotsAnyStylist(salonId, date, durationMinutes)` is just the above, repeated for every
stylist currently marked `active` at that salon (`stylist_salon.status = 'active'`), with
all their results concatenated — this backs the "any available stylist" booking path where
the customer doesn't care which stylist they get.

## 4. `blockWalkIn` — the <5-second front-desk quick-add

A receptionist adding a walk-in customer needs this to be near-instant. So this does far
less than `freeSlots`:

1. Compute the same "busy" windows as Step 4 above (breaks/leave, other walk-ins, real
   bookings and holds) — but **skip** Steps 1–3 entirely (no salon-hours or working-hours
   check).
2. Check the requested `(start, start + durationMinutes)` window doesn't overlap any of
   those busy windows. If it does, reject with a 409 Conflict.
3. If it's clear, insert one `walk_in_block` row. Done.

The deliberate omission of the working-hours check is a real design choice, not an
oversight: if a stylist is actually standing at the salon working a walk-in customer right
now, the software shouldn't second-guess that just because nobody entered a matching
`stylist_availability` row for today. The system still refuses to double-book an actual
conflict — it just doesn't gatekeep on the schedule.

## 5. Worked example

Salon hours (Tuesday): 10:00–19:00.
Stylist's Tuesday weekly template: working 10:00–18:00, break 13:00–14:00.
Slot granularity: 15 minutes. Requested service: 45 minutes.

Existing state on this particular Tuesday:
- A confirmed booking occupies 11:00–11:45 (from bmp-booking).
- Someone else is mid-checkout holding 15:00–15:30 (`slot_lock`, from bmp-booking).
- A receptionist walk-in-blocked 16:00–16:30 earlier that morning.

Working through the steps:
1. Salon hours: 10:00–19:00.
2. Working windows (weekly template, no exception/leave today): 10:00–18:00.
3. Intersect with salon hours: still 10:00–18:00 (salon hours are wider here).
4. Subtract busy time:
   - Break 13:00–14:00
   - Booking 11:00–11:45
   - Checkout hold 15:00–15:30
   - Walk-in block 16:00–16:30

   Remaining free windows: **10:00–11:00**, **11:45–13:00**, **14:00–15:00**,
   **15:30–16:00**, **16:30–18:00**.
5. Slice into 15-minute-grid starts, keeping only those with 45 minutes free:
   - 10:00–11:00 → starts at 10:00, 10:15 (10:15+45=11:00, fits exactly)
   - 11:45–13:00 → starts at 11:45, 12:00, 12:15 (12:15+45=13:00, fits exactly)
   - 14:00–15:00 → starts at 14:00 and 14:15 (14:15+45=15:00, fits exactly)
   - 15:30–16:00 → too short for 45 minutes, no valid start
   - 16:30–18:00 → starts at 16:30 through 17:15

The stylist is NOT free for a 45-minute service at, say, 12:30, even though 12:30 itself
looks free on a calendar — because 12:30 + 45 minutes runs into the 13:00 break.

## 6. Open design questions this session answered (unratified — see CONTEXT.md)

These were flagged back in Session 4/5 as needing a paper exercise against 3 real salons
before any table was built. That paper exercise (Q1–Q6) was signed off by Darshan alone, as
was this implementation of it. **Shivam and Achyuth should review and either ratify or
amend the choices below** — nothing here is unrecoverable, it's all in application code,
not a schema decision.

| # | Question | Answer chosen |
|---|---|---|
| Q1 | Fixed grid vs. duration-derived slots? | Both: start times are grid-aligned (`slot_granularity_minutes`), but the *length* required is whatever the caller asks for |
| Q2 | How does a receptionist block a walk-in in <5 seconds? | One overlap check, one insert — no working-hours validation (see §4) |
| Q3 | Recurring template vs. per-day exceptions for breaks? | Both — they stack. An exception can also fully replace the day's working windows |
| Q4 | Leave with existing bookings already taken? | **Not handled.** Marking leave does not touch already-CONFIRMED bookings. This is a real gap — belongs to a `booking_disruption` / reschedule-notification flow that doesn't exist yet |
| Q5 | Salon hours vs. individual stylist hours on conflict? | Salon hours always win (the outer bound) |
| Q6 | Multi-service bookings spanning slot boundaries? | Not this method's concern — `durationMinutes` is the caller's total; splitting across stylists is booking's job |

## 7. Other things worth knowing

- **Timezone:** all of this assumes Asia/Kolkata (`com.bmp.common.time.BmpTimeZone`),
  hardcoded — deliberate, since BMP only operates in Bengaluru today. This becomes a
  per-salon setting the day BMP expands outside IST, not before.
- **Day-of-week convention:** `0 = Sunday … 6 = Saturday` (matches the V003 migration's
  column comments). This has not been independently verified against how those columns are
  actually populated anywhere else in the codebase — worth double-checking against real
  data before trusting it blindly.
- **No caching, no locking:** every call recomputes from scratch and reads live from both
  services. This is correct but not optimized — fine at pilot scale (a few hundred
  bookings/day across a handful of salons), worth revisiting if response times become a
  problem.
- **`blockWalkIn` has no distributed lock either.** Two receptionists confirming the exact
  same walk-in slot within the same instant could theoretically both pass the overlap
  check before either insert lands. Low real-world risk (a human is physically present
  confirming this), but worth knowing if it's ever automated further.
