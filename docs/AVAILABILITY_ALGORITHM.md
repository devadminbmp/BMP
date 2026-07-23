# The Availability Algorithm — How `freeSlots()` / `blockWalkIn()` Work

**Status:** First real implementation, Session 9. Previously `AvailabilityApi` was an
intentional stub (see its own javadoc: "must be designed on paper against 3 real salons'
actual weeks BEFORE any table behind this interface is created") — this document explains
what now backs it, in full detail, top to bottom.

**Where the code lives:**

| File | Role |
|---|---|
| `bmp-salon/.../api/AvailabilityApi.java` | The contract. Booking consumes ONLY this interface and never touches any table below directly. |
| `bmp-salon/.../services/AvailabilityService.java` | The implementation — all the interval math lives here. |
| `bmp-salon/.../controllers/AvailabilityController.java` | REST surface: `/api/v1/availability/slots`, `/slots/any`, `/walk-in`. |
| `bmp-salon/.../repositories/StylistAvailabilityRepository.java` | Query methods for `stylist_availability`. |
| `bmp-salon/.../repositories/WalkInBlockRepository.java` | Query methods for `walk_in_block`. |
| `bmp-salon/.../repositories/SalonHoursRepository.java` | Query methods for `salon_hours`. |
| `bmp-salon/.../repositories/SalonPolicyRepository.java` | Query methods for `salon_policy`. |
| `bmp-salon/.../client/BookingServiceClient.java` | Feign client — the one call out to bmp-booking. |
| `bmp-salon/.../config/FeignInternalKeyConfig.java` | Attaches the service-to-service auth header to that Feign call. |
| `bmp-booking/.../controllers/BookingAvailabilityController.java` | The internal endpoint bmp-salon calls. |
| `bmp-booking/.../services/BookingAvailabilityService.java` | Builds the busy-window response on the bmp-booking side. |
| `bmp-common/.../time/BmpTimeZone.java` | The one shared timezone constant (`Asia/Kolkata`) both services use to convert between `Instant` and `LocalTime`. |

This document assumes no prior context beyond CONTEXT.md's Module 2 (Salon) and Module 3
(Booking) schema tables, which are reproduced in full below rather than just referenced,
so this stands alone.

---

## 1. The problem, precisely

**Input:** a salon, a stylist, a calendar date, and a service duration in minutes.
**Output:** every start time on that date at which that stylist could be booked for a
service of that length, given everything currently true about their schedule and the
salon's.

"Everything currently true" breaks down into six independent facts, three of which change
constantly (bookings, checkout holds, walk-ins) and three of which are closer to
configuration (the stylist's declared hours, the salon's declared hours, the booking
grid). The algorithm's whole job is combining all six correctly.

## 2. Why two microservices are involved

BMP is split into independently-deployable services, one Postgres schema each (see
CONTEXT.md's Architecture section). The six facts above are split across two of them:

### bmp-salon (`salon_schema`) — the "configuration" side

**`stylist_availability`** (from `V003__availability_model.sql`):

```
id                UUID PK
stylist_id        UUID NOT NULL   -- FK -> stylist.id
salon_id          UUID NOT NULL   -- a stylist's availability is declared per salon
rule_type         VARCHAR(20)     -- 'weekly_template' | 'exception' | 'leave'
day_of_week       SMALLINT        -- 0-6, used only when rule_type = weekly_template
specific_date     DATE            -- used only when rule_type = exception or leave
slot_type         VARCHAR(10)     -- 'working' | 'break' | 'leave'
start_time        TIME            -- NULL for a full-day leave row
end_time          TIME
blocks_booking    BOOLEAN         -- true for break/leave rows, false for working rows
```

**`walk_in_block`**:

```
id                    UUID PK
salon_id              UUID NOT NULL
stylist_id            UUID NOT NULL
block_date            DATE NOT NULL
start_time            TIME NOT NULL   -- expected to already be grid-aligned
duration_minutes      INT NOT NULL
created_by_staff_id   UUID            -- logical ref -> salon_staff or bmp_staff, nullable
```

**`salon_hours`** (from `V002__salon_schema.sql`): one row per salon per day of week,
`open_time`/`close_time`. No row for a given day of week means the salon is closed that
day.

**`salon_policy`**: one row per salon, holding `slot_granularity_minutes` among other
policy fields (cancellation windows, prepayment requirements — not relevant here).

### bmp-booking (`booking_schema`) — the "what's actually happening right now" side

**`booking_service_item`** (from `V002__booking_schema.sql`): one row per service within a
booking. Relevant columns: `assigned_stylist_id`, `service_start`/`service_end`
(`TIMESTAMPTZ`), `item_status` (`active`/`removed`/`completed`). The parent `booking` row
also has a `status` (`PENDING`/`CONFIRMED`/`ARRIVED`/`IN_SERVICE`/`COMPLETED`/`CANCELLED`/
`NO_SHOW` — see `BookingStatus.java`'s state machine).

**`slot_lock`**: a short-lived hold (typically ~5 minutes) taken while a customer is mid-
checkout, before payment has actually confirmed the booking. Columns: `stylist_id`,
`lock_date`, `start_time`/`end_time` (`TIME`), `expires_at` (`TIMESTAMPTZ`),
`release_reason` (`NULL` while still active).

### The cross-service call

`AvailabilityApi` is declared and implemented in bmp-salon — it's fundamentally a
salon/stylist-schedule question, and booking is explicitly forbidden from touching
`stylist_availability`/`walk_in_block` directly (that's the whole point of the interface
boundary). But two of the six facts (confirmed bookings, active locks) live in
bmp-booking's schema, which bmp-salon has no direct database access to (schema-per-service
is enforced at the deploy boundary, not just convention).

So `AvailabilityService` makes exactly one outbound HTTP call per `freeSlots` invocation,
over Spring Cloud OpenFeign, resolved through Eureka (`lb://bmp-booking-service`):

```
GET /api/v1/bookings/internal/busy-windows?stylistId=<uuid>&date=2026-07-28
```

authenticated with the shared `X-Internal-Service-Key` header (the same mechanism bmp-auth
uses when it calls bmp-salon — see `FeignInternalKeyConfig` in both services). The endpoint
is annotated `@PreAuthorize("hasRole('SERVICE')")`, so it rejects any request that doesn't
present that key, including a normal customer or salon-staff JWT.

**Response shape** (`BusyWindowsResponse`):

```json
{
  "windows": [
    { "start": "11:00:00", "end": "11:45:00", "source": "booking" },
    { "start": "15:00:00", "end": "15:30:00", "source": "slot_lock" }
  ]
}
```

On the bmp-booking side, `BookingAvailabilityService.getBusyWindows(stylistId, date)`
builds this by:
1. Converting `date` to a `[dayStart, dayEnd)` `Instant` range in `Asia/Kolkata`
   (`date.atStartOfDay(BmpTimeZone.ZONE).toInstant()` and the same for `date.plusDays(1)`).
2. Querying `booking_service_item` for rows where `assigned_stylist_id` matches, whose
   `[service_start, service_end)` overlaps that range, `item_status = 'active'`, **and**
   whose parent `booking.status <> CANCELLED`. That last join matters: `item_status` alone
   is not updated by the cancel flow today, so relying on it in isolation would still show
   a cancelled booking's slot as busy — a real bug this implementation specifically avoids
   by joining to `booking`.
3. Querying `slot_lock` for rows matching `stylist_id` + `lock_date`, where
   `release_reason IS NULL` (never released) and `expires_at > now()` (hasn't lapsed).
4. Converting each match's `Instant` timestamps (or `TIME` strings, for `slot_lock`) into
   `LocalTime` and returning the combined list, tagged with a `source` field purely for
   debuggability.

## 3. The algorithm, step by step (`freeSlots`)

Signature: `List<Slot> freeSlots(UUID salonId, UUID stylistId, LocalDate date, int durationMinutes)`

All internal time math is done in **minutes since midnight** (a plain `int`), not
`LocalTime` objects directly — this makes interval arithmetic (below) simple integer
comparisons instead of repeated `LocalTime` method calls.

### Step 1 — Resolve the day of week

```java
int dayOfWeek = date.getDayOfWeek().getValue() % 7;  // Sunday=0 .. Saturday=6
```

`LocalDate.getDayOfWeek().getValue()` returns 1=Monday..7=Sunday (ISO-8601). Taking `% 7`
maps Sunday (7) to 0 and leaves Monday..Saturday as 1..6 — this is the convention assumed
to match `stylist_availability.day_of_week` / `salon_hours.day_of_week`'s column comments
in the migration. **This has not been independently verified against real seed data** —
flagged again in §7.

### Step 2 — Look up the salon's hours for that day of week

```java
Optional<SalonHours> hours = salonHoursRepo.findBySalonIdAndDayOfWeek(salonId, dayOfWeek);
if (hours.isEmpty()) return List.of();  // salon closed this day — nothing else matters
```

If present, `salonWindow = Interval(toMinutes(openTime), toMinutes(closeTime))`.

### Step 3 — Determine the stylist's working windows for this exact date

This is the method `workingWindows(stylistId, salonId, date)`, and it has three tiers,
checked in this order:

**Tier A — full-day leave.** Query `stylist_availability` for
`rule_type = 'leave' AND specific_date = date`. If any matching row has a `NULL`
`start_time` or `end_time`, treat the entire day as unavailable and return an empty list
immediately — nothing later in the algorithm runs.

**Tier B — a date-specific working exception.** Query
`rule_type = 'exception' AND specific_date = date`, filtered to `slot_type = 'working'`.
If any such rows exist, they **completely replace** the weekly template for this date — not
added on top of it. This is the mechanism for "this stylist doesn't normally work
Tuesdays, but is covering this particular Tuesday" or "normally 10am–6pm, but only
2pm–6pm today because of a personal commitment." If this tier finds rows, their
`(start_time, end_time)` pairs become the working windows and Tier C is never consulted.

**Tier C — the weekly template (default case).** Query
`rule_type = 'weekly_template' AND day_of_week = <computed above>`, filtered to
`slot_type = 'working'`. These rows' `(start_time, end_time)` pairs are the working
windows. If a stylist simply has no `weekly_template` row for this day of week (and no
exception either), the result is an empty list — they're not scheduled to work at all.

### Step 4 — Clip working windows to the salon's operating hours

```java
working = intersectAll(working, salonWindow);
```

Every working window from Step 3 is intersected against the single `salonWindow` interval
from Step 2. **This is the Q5 design decision: salon hours are the outer bound, always.**
A stylist's own declared hours can never make them bookable outside when the salon itself
is open — if their `stylist_availability` row says 8am–8pm but the salon's `salon_hours`
row says 10am–7pm, the effective window is 10am–7pm. (The reverse was also considered —
stylist hours being the tighter, authoritative bound — but salon hours winning was judged
the safer default: it prevents a data-entry mistake in one stylist's schedule from ever
producing a bookable slot outside the building's actual opening hours.)

### Step 5 — Collect everything that blocks (subtracts from) the working windows

Four independent sources are gathered into one list of "busy" intervals, via three
different repository/service calls plus one already-covered by Step 3's leave check for
full days:

**5a. Recurring breaks** — `breakAndLeaveWindows()`'s first block: `weekly_template` rows
for this day of week where `blocks_booking = true` (i.e. `slot_type = 'break'`) and both
`start_time`/`end_time` are set. Typically a lunch break entered once and repeating every
week.

**5b. Date-specific breaks or partial-day leave** — the same method's second and third
blocks: `exception` rows for this date with `blocks_booking = true`, and `leave` rows for
this date that DO have a start/end time set (a partial-day leave — the full-day case was
already handled and short-circuited in Step 3, Tier A). Both get added to the busy list
the same way.

**5c. Walk-in blocks** — `walkInWindows()`: every `walk_in_block` row for this
`stylist_id`/`date`, converted to `Interval(start, start + duration_minutes)`.

**5d. Live bookings and checkout holds** — `bookingBusyWindows()`: the one Feign call
described in §2, converting the response's `LocalTime` pairs into `Interval`s.

### Step 6 — Subtract the busy intervals from the working intervals

```java
List<Interval> free = subtractAll(working, blocking);
```

This is real interval-subtraction, not a simple filter, because a single busy interval can
split one working interval into two (e.g. a 10am–6pm working window with a 1pm–2pm break
inside it becomes two free windows: 10am–1pm and 2pm–6pm). The implementation:

1. **Merge overlapping busy intervals first** (`mergeOverlapping`). If a booking and a
   walk-in-block happen to overlap in the input (shouldn't normally happen, but the
   algorithm doesn't assume it can't), they're combined into one interval before
   subtraction, so the subtraction step below doesn't have to reason about overlapping
   subtrahends.
2. **For each merged busy interval, split every working interval it overlaps.** For a
   working interval `[wStart, wEnd)` and a busy interval `[bStart, bEnd)` that overlaps it:
   - If `wStart < bStart`, keep `[wStart, bStart)` (the part before the busy interval).
   - If `wEnd > bEnd`, keep `[bEnd, wEnd)` (the part after it).
   - If neither condition holds (the busy interval fully covers the working interval),
     nothing survives from that working interval.
   - Working intervals the busy interval doesn't overlap at all pass through unchanged.
3. Repeat for every busy interval in sequence, each time operating on the result of the
   previous subtraction. What remains after all busy intervals have been applied is the
   final list of genuinely free windows.

### Step 7 — Slice free windows into a bookable grid

```java
int granularity = salonPolicyRepo.findBySalonId(salonId)
        .map(SalonPolicy::getSlotGranularityMinutes)
        .orElse(15);  // default if a salon has no policy row yet
```

For each free interval `[start, end)`:
1. Round `start` **up** to the next multiple of `granularity` (if `start` is already
   grid-aligned, it's used as-is — e.g. an interval starting exactly at 11:45 with a
   15-minute grid stays at 11:45, since 45 is itself a multiple of 15).
2. Starting from that grid-aligned point, step forward by `granularity` minutes at a time.
   At each step, if `step + durationMinutes <= end` (i.e. a full, uninterrupted run of the
   requested duration fits before this free window ends), emit a `Slot(start, start +
   durationMinutes, stylistId)`. Otherwise stop advancing within this interval — the
   remaining space is too short for another valid start.

This is why a free window can be "long enough to look free" on a calendar view but still
not produce a valid slot at every grid point within it near its edges — only points where
the FULL requested duration fits before the window's end are kept.

### `freeSlotsAnyStylist` — the "any available stylist" path

```java
for (StylistSalon link : stylistSalonRepo.findBySalonIdAndStatus(salonId, "active")) {
    all.addAll(freeSlots(salonId, link.getStylistId(), date, durationMinutes));
}
```

Simply runs the entire algorithm above once per stylist currently marked `active` at that
salon, and concatenates every result. No stylist-specific ranking or preference logic
exists yet — the customer picks whichever resulting slot they want and the assignment is
whatever stylist that slot belongs to. There is currently no de-duplication or sorting of
the combined list by time — a follow-up if the "any available" UI wants slots presented in
chronological order across stylists rather than grouped by stylist.

## 4. `blockWalkIn` — the front-desk quick-add

This is intentionally a much shorter code path than `freeSlots`, because a receptionist
using this at the counter needs it to feel instant.

```java
public void blockWalkIn(UUID salonId, UUID stylistId, LocalDate date, LocalTime start, int durationMinutes) {
    Interval requested = new Interval(toMinutes(start), toMinutes(start) + durationMinutes);

    List<Interval> busy = new ArrayList<>();
    busy.addAll(breakAndLeaveWindows(stylistId, salonId, date));
    busy.addAll(walkInWindows(stylistId, date));
    busy.addAll(bookingBusyWindows(stylistId, date));

    for (Interval b : busy) {
        if (requested.overlaps(b)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "STYLIST_NOT_FREE: ...");
        }
    }

    walkInRepo.save(new WalkInBlock(salonId, stylistId, date, start.toString(), durationMinutes, null));
}
```

Notice what's **missing** compared to `freeSlots`: no salon-hours lookup (Step 2), no
stylist working-window resolution (Step 3), no clipping (Step 4). It goes straight to
gathering busy intervals (breaks/leave, other walk-ins, live bookings/locks — the same
sources as Step 5 above) and does a single linear overlap check against the requested
window. If nothing overlaps, it inserts one `walk_in_block` row and returns; the calling
controller responds `201 Created`. If something overlaps, it throws, and the controller
surfaces `409 Conflict`.

**Why skip the working-hours check?** This is a deliberate product decision, not an
oversight (documented directly in the code's javadoc): if a stylist is physically at the
salon handling a walk-in right now, the system shouldn't refuse to record that just
because nobody entered a matching `stylist_availability` row for today, or because the
walk-in happens to fall slightly outside their declared hours (staying 15 minutes late for
a walk-in is common in practice). What the system will never do is let a receptionist
double-book a stylist who's already committed to something else — that's the one check
that always runs.

## 5. Worked examples

### Example A — the base case, with a break, a booking, a hold, and a walk-in

Salon hours (Tuesday): 10:00–19:00.
Stylist's Tuesday weekly template: working 10:00–18:00, break 13:00–14:00.
Slot granularity: 15 minutes. Requested service: 45 minutes.

Existing state on this particular Tuesday:
- A confirmed booking occupies 11:00–11:45.
- Someone else is mid-checkout holding 15:00–15:30 (`slot_lock`).
- A receptionist walk-in-blocked 16:00–16:30 earlier that morning.

Working through the steps:

1. Salon hours: 10:00–19:00.
2. Working windows (weekly template; no exception/leave today): 10:00–18:00.
3. Intersect with salon hours: still 10:00–18:00 (salon hours are the wider of the two here).
4. Busy intervals to subtract: break 13:00–14:00, booking 11:00–11:45, hold 15:00–15:30,
   walk-in 16:00–16:30.
5. Subtracting all four from 10:00–18:00 leaves five free windows:
   **10:00–11:00**, **11:45–13:00**, **14:00–15:00**, **15:30–16:00**, **16:30–18:00**.
6. Slicing each into a 15-minute grid, keeping only starts where 45 minutes fit:

   | Free window | Valid 45-min starts |
   |---|---|
   | 10:00–11:00 (60 min) | 10:00, 10:15 |
   | 11:45–13:00 (75 min) | 11:45, 12:00, 12:15 |
   | 14:00–15:00 (60 min) | 14:00, 14:15 |
   | 15:30–16:00 (30 min) | *(none — too short)* |
   | 16:30–18:00 (90 min) | 16:30, 16:45, 17:00, 17:15 |

Final result: **11 bookable start times** across the day. Notice 12:30 is NOT in the
list even though it sits inside the 11:45–13:00 free window — 12:30 + 45 minutes = 13:15,
which runs into the 13:00 break, so it's correctly excluded even though a naive "is this
moment free" check would have said yes.

### Example B — a date-specific exception overriding the template

Same stylist, same salon, but this Tuesday they have an `exception` row:
`slot_type='working', start_time=14:00, end_time=17:00` (they're covering a shorter,
different shift than their usual Tuesday pattern — maybe a personal appointment in the
morning).

Step 3 finds this exception row in Tier B and uses `14:00–17:00` as the ENTIRE working
window for the day — the weekly template's `10:00–18:00` is never consulted. If no other
break/booking exists that day, the free windows (after clipping to salon hours, which are
wider) are simply `14:00–17:00`.

### Example C — full-day leave

Same stylist has a `leave` row for this date with `start_time`/`end_time` both `NULL`.
Step 3, Tier A catches this immediately and `freeSlots` returns an empty list without
querying breaks, walk-ins, or calling bmp-booking at all — there's no point computing busy
windows for a day the stylist isn't working regardless.

Per Q4 in §6, this does **not** cancel or reschedule any booking that was already
CONFIRMED for this stylist on this date before the leave was entered — that booking still
exists and will still show up if anyone queries it directly. Only future `freeSlots` calls
are affected.

### Example D — a walk-in request that gets rejected

Continuing Example A's state (11:00–11:45 booked, 15:00–15:30 held, 16:00–16:30
walk-in-blocked). A receptionist tries to `blockWalkIn` this stylist at 11:20 for 30
minutes (`11:20–11:50`).

The busy-interval check finds the existing 11:00–11:45 booking, and `11:20–11:50` overlaps
it (`11:20 < 11:45` and `11:50 > 11:00` — both overlap conditions hold). The call throws
`409 Conflict` with reason `STYLIST_NOT_FREE`, and no `walk_in_block` row is inserted.

### Example E — "any available stylist"

A salon has three active stylists: A (free 10:00–11:00, 14:00–15:00 per Example A's
shape), B (fully booked all day), C (on leave). `freeSlotsAnyStylist` calls `freeSlots`
for each: A returns its usual list, B returns an empty list (no free windows survive
subtraction), C returns an empty list immediately (Step 3 Tier A leave check). The combined
result is just A's slots, each tagged with A's `stylistId` — the caller has no way to tell
from the response alone that B and C were even considered, which is fine for this use case
(the customer only cares that *someone* is free at a given time).

## 6. Open design questions this session answered (unratified — see CONTEXT.md)

These were flagged back in Session 4/5 as needing a paper exercise against 3 real salons
before any table was built. That paper exercise (Q1–Q6) was signed off by Darshan alone, as
was this implementation of it. **Shivam and Achyuth should review and either ratify or
amend the choices below** — nothing here is unrecoverable, it's all in application code,
not a schema decision, so any answer can change without a migration.

| # | Question | Answer chosen | Where it's implemented |
|---|---|---|---|
| Q1 | Fixed grid vs. duration-derived slots? | Both: start times are grid-aligned (`slot_granularity_minutes`), but the *length* required is whatever the caller asks for | Step 7 / `sliceIntoSlots()` |
| Q2 | How does a receptionist block a walk-in in <5 seconds? | One overlap check, one insert — no working-hours validation | §4 / `blockWalkIn()` |
| Q3 | Recurring template vs. per-day exceptions for breaks? | Both — they stack. An exception can also fully replace the day's working windows (not just add a break) | Step 3 (Tier B) and Step 5a/5b |
| Q4 | Leave with existing bookings already taken? | **Not handled.** Marking leave does not touch already-CONFIRMED bookings. This is a real gap — belongs to a `booking_disruption` / reschedule-notification flow that doesn't exist yet | Step 3, Tier A — deliberately does nothing to `booking_schema` |
| Q5 | Salon hours vs. individual stylist hours on conflict? | Salon hours always win (the outer bound) | Step 4 / `intersectAll()` |
| Q6 | Multi-service bookings spanning slot boundaries? | Not this method's concern — `durationMinutes` is the caller's total; splitting a multi-service booking across stylists is booking's job | Out of scope by design |

## 7. Other things worth knowing

- **Timezone:** all conversions between bmp-booking's `Instant` timestamps and bmp-salon's
  `LocalTime`/`LocalDate` values go through `com.bmp.common.time.BmpTimeZone.ZONE`
  (`Asia/Kolkata`), hardcoded. Deliberate — BMP only operates in Bengaluru today (see
  CONTEXT.md's Target Market section). This becomes a per-salon column the day BMP expands
  outside IST, not before.
- **Day-of-week convention:** `0 = Sunday … 6 = Saturday`
  (`date.getDayOfWeek().getValue() % 7`), matching the V003 migration's column comments.
  This has **not** been independently verified against how `stylist_availability.day_of_week`
  or `salon_hours.day_of_week` rows are actually populated anywhere else in the codebase
  (there's no seed data or admin UI writing them yet) — worth a real end-to-end check
  before trusting it against production data.
- **No caching.** Every `freeSlots` call re-queries all four bmp-salon tables and makes a
  fresh Feign call to bmp-booking — nothing is cached or precomputed. This is correct but
  not optimized; fine at pilot scale (a handful of salons, low booking volume), worth
  revisiting (e.g. caching salon hours/policy, which change rarely) if response times
  become a problem under real load.
- **No distributed lock on `blockWalkIn`.** Two receptionists confirming the exact same
  walk-in slot within the same instant could theoretically both pass the overlap check
  before either insert lands, producing a double-booked walk-in. Low real-world risk (a
  human is physically present confirming this at a single front desk), but worth knowing
  if this ever gets automated further (e.g. a customer-facing walk-in app instead of
  staff-only).
- **No authorization on any of these endpoints yet.** `/api/v1/availability/**` is open to
  any valid token today, same as booking/payment/review/rewards as of this session (see
  CONTEXT.md's per-service authorization status) — a follow-up ticket, not specific to
  availability.
- **`freeSlotsAnyStylist` doesn't sort or merge results.** Slots are grouped by stylist in
  the order `stylist_salon` returns them, not sorted chronologically across stylists. If
  the "any available" UI wants a single time-ordered list, that sort needs to happen at
  the caller (or be added here) — not done yet.
