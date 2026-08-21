# Coupons and rewards — bmp-rewards

How a discount goes from being created by staff to reducing what a customer pays.

---

## 1. The gap this closed

Session 20 built coupon **issuing** — admin campaigns, support goodwill, targeting, caps. But
nothing ever **validated or applied** a code at checkout. Every coupon in the system was
decorative: staff could create them, customers could be told about them, and no booking was ever
a rupee cheaper.

Session 22 built the other half.

---

## 2. Two entry points, one set of rules

| | Who calls it | Writes? | On refusal |
|---|---|---|---|
| `POST /api/v1/coupons/quote` | The customer, typing a code | No | Returns a sentence |
| `POST /api/v1/coupons/internal/redeem` | bmp-booking, in the booking transaction | Yes | Throws 409 |

They share `evaluate()` deliberately. If they could disagree, a customer would be shown
"₹300 off" and then charged full price — and the two would drift apart the first time one was
edited without the other.

**The discount is computed server-side.** The client sends a code and a basket; the server says
what it's worth. Accepting a discount amount from a request body is how a marketplace gives away
money.

---

## 3. The checks, in order

Order matters — "this coupon has expired" is more useful than "you've already used this coupon"
when both are true, because the first tells them to stop trying.

1. Status — paused / revoked
2. Active window
3. **Minimum spend** — placed early because it's the one the customer can fix right now
4. Audience — all users · selected users · new users · referred users
5. Salon scope
6. Per-user limit
7. Total usage cap

Every refusal names the actual reason. *"This coupon isn't valid"* produces a support ticket;
*"this code needs a minimum spend of ₹1,000"* doesn't.

### Audience checks

- **selected_users** → membership of `coupon_user`. This is what makes a leaked support coupon
  worthless to a stranger.
- **new_users** → bmp-booking tells us whether it's their first booking. It owns that fact;
  duplicating a "has ever booked" query here would be a second source of truth.
- **referred_users** → an unflagged row in `referral`.

### The discount

Percentages are stored as basis points (2000 = 20%), so the arithmetic is integer throughout —
money never touches a float. Capped by the coupon's `max_discount_paise` **and** by the basket
itself: a discount larger than the basket would produce a negative total, which downstream
becomes a refund nobody intended.

---

## 4. Two concurrency decisions

**The coupon row is locked during redemption.** Two customers claiming the last use of a limited
coupon at the same instant would both pass a count-then-insert check and both get the discount.
Classic, and invisible until the offer is popular enough to matter. The lock is one row held for
milliseconds; the alternative (tolerating overshoot) is defensible for a ₹50 code and
indefensible for a ₹5,000 launch offer, and making it conditional means the risky path is the
one nobody tests.

**Redemption is idempotent per booking.** bmp-booking can retry for reasons unrelated to
coupons — a Feign timeout, a client resend. Without the check, one booking would consume two
uses of the customer's allowance.

---

## 5. `commissionBase` — who actually pays

`redeem` returns `commissionBase` so the booking can snapshot the right basis:

- `pre_discount` — the **salon** absorbs the discount (commission on the full price)
- `post_discount` — **BMP** absorbs it (commission on what was actually paid)

Snapshotted, never recalculated. Working it out later — after a coupon has been paused or
edited — would give a different answer than the one the salon agreed to.

Staff don't choose this directly: `CouponAdminService` derives it, so a support agent can't
accidentally bill a salon for BMP's apology.

---

## 6. Referrals

`GET /referrals/my-code` · `POST /referrals/internal/attribute`

Codes are created on first request, not at signup — most customers never share one. Rewards are
**frozen onto the referral row** at attribution: if the programme changes from ₹150 to ₹50 next
month, everyone who already referred someone still gets what they were promised.

Self-referral and already-referred are **recorded as fraud reasons rather than refused**. You
want to see that someone tried, and a hard refusal teaches them exactly which check to work
around next time. The reward simply never completes.

---

## 7. A hole this pass closed

`bmp-rewards`' `public-paths` still fell back to the code default of `/**`, meaning **nothing in
the service was authenticated**. The principal was always null, so the `@PreAuthorize` on these
endpoints could not have worked and `/internal/redeem` would have been callable by anyone who
could reach the port. Coupons are money — that isn't theoretical. Now tightened to health and
docs only.

---

## 8. Wired into booking (Session 22b)

Coupons now actually reduce what customers pay.

```
CreateBookingRequest { …, couponCode }
   ↓  bmp-booking validates every slot, inserts the booking
   ↓  POST /coupons/internal/redeem { code, userId, salonId, bookingId, basketPaise, isFirstBooking }
   ↓  ← { couponId, discountPaise, commissionBase }
   ↓  booking.applyDiscount(...)  → V005 columns
BookingResponse { grossAmountPaise, discountPaise, finalAmountPaise }
```

**Ordering matters.** Redemption happens *after* slot validation and the booking insert.
Redeeming consumes one of the customer's uses, so it must not fire for a booking that then
fails on an unavailable slot — they'd lose a single-use coupon to a booking that never existed.
It's inside the same `@Transactional`, so anything failing below rolls the redemption back.

**A bad code fails the booking.** Not silently ignored, not applied at zero. The customer chose
to book at a price they were shown; charging full price instead is something they'd only
discover on their card statement.

**`FeignException`, not `ResponseStatusException`.** Feign surfaces a remote error as its own
type, so the two cases have to be told apart by status code — 409/404 is a refusal to pass
through, anything else is an outage. Getting this wrong reports an exhausted coupon as "try
again later", and the customer retries forever.

**Cancelling releases the coupon**, best-effort: a failure there must not block the
cancellation, because being unable to cancel is far worse than a coupon needing manual
restoration. The log names the exact coupon if it happens.

### V005 — why the booking stores its own copy

`coupon_usage` answers "how many times was this coupon used" and belongs to the coupon.
`gross_amount_paise` / `discount_paise` / `commission_base` answer "what did this customer agree
to pay, and why", and belong to the booking.

If a coupon is later paused or its percentage edited, a booking made under the old terms must
still show the old numbers. A booking that re-derives its price from a mutable coupon row is one
whose total silently changes after the customer agreed to it. Same principle as
`policy_snapshot`: freeze the terms, don't recompute them.

`gross_amount_paise` is stored rather than computed as final + discount, because
`final_amount_paise` will later be touched by refunds — at which point the arithmetic breaks and
the original agreement becomes unrecoverable.

### Customer app

`CouponField` — collapsed to one line by default. A permanently-open promo box tells someone who
wasn't looking for a discount that they're probably paying too much, and they leave to hunt for
a code. It quotes read-only before booking, shows the saving when applied, and displays refusals
**verbatim** because the server writes them to be actionable.

Mock codes: `BMPWELCOME` (20%, capped at ₹300), `BMPBIG` (min-spend refusal), `BMPGONE` (fully
claimed).

---

## 9. Not done

- **Nothing calls `redeem` yet.** bmp-booking's create path doesn't know about coupons — this is
  the next piece, and until it lands coupons still don't reduce anyone's bill.
- **Nothing calls `release` either**, so a cancelled booking silently burns the customer's
  coupon. They will notice.
- **Referral payout doesn't exist.** Attribution is recorded; no reward is credited. Needs a
  `booking.completed` consumer and a wallet credit, which needs payments.
  **Do not advertise a referral programme until this is wired.**
- `checkout_discount` (V002) is unused — it's the natural home for the frozen commission basis
  once bookings apply coupons.
- Loyalty and win-back remain schema-only, flagged off.
- No tests. `CouponRedemptionService.discountFor` and the cap logic are pure functions and the
  obvious first thing to test.
