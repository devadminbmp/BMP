# Coupon requests, allowances, and who may approve what

The approval workflow added in Session 31. Companion to `COUPONS_AND_REWARDS.md`, which covers
how a coupon is validated and redeemed; this covers how one comes to exist.

---

## The problem this solves

Session 22 gave support hard limits — ₹500, 20%, one recipient, thirty days — and made them
configuration rather than constants. That is the right wall. But a wall with no gate has a
predictable failure mode, and it shows up in the first week:

> *"The salon cancelled this customer's wedding booking. ₹500 isn't enough. What do I do?"*

The answer was a 403 and the end of the conversation. In practice that means one of two things,
and both are worse than the problem:

- the agent gives up and the customer leaves, or
- somebody borrows an admin login "just for this one".

**A refusal with no path forward doesn't enforce a policy. It makes people route around it.**

There was a second gap. Per-coupon limits are not a budget: one agent could issue a hundred
₹500 coupons in an afternoon — every one within policy — and nobody would know until the
month's numbers came in.

---

## Three ways a coupon now comes into existence

| Path | Who | Limits |
|---|---|---|
| **Direct issue** | Ops / superadmin | None |
| **Direct issue** | Support agent | Per-coupon limits **and** a rolling allowance |
| **Approved request** | Support (over their limit) or a salon owner | Whatever the approving admin grants |

### Why a salon owner can never issue directly

A coupon can be funded out of **BMP's commission** (`commission_base = post_discount`) rather
than by the salon. "Who pays for this?" is not the beneficiary's question to answer. So an owner
raises a request, an admin decides the funding, and the coupon is created centrally.

An owner's request is **forced to their own salon from their token** — a platform-wide ask isn't
possible, rather than being possible and refused later. By approval time it would look like a
decision someone made.

---

## The allowance

Measured over a **rolling window**, not a calendar month. A calendar reset creates a
use-it-or-lose-it incentive at month-end, which is the last thing you want attached to an
apology budget.

| Setting | Default | Policy key |
|---|---|---|
| Window | 7 days | `support_allowance_period_days` |
| Coupons per window | 10 | `support_allowance_max_count` |
| Total value per window | ₹3,000 | `support_allowance_max_paise` |
| Request auto-expiry | 14 days | `request_auto_expire_days` |

⚠️ **These are starting points, not a business position.** Deliberately tight — it is far easier
to raise a limit that turned out to be annoying than to explain a month of unnoticed giveaways.
Decide them properly before launch.

### Two judgement calls worth knowing

**Percentage coupons count at their cap.** A "20%, max ₹400" coupon consumes ₹400 of the
allowance even though most redemptions are worth less. The allowance measures **exposure**, and
the honest worst case is the cap. Counting an average would let someone issue far more real
liability than the budget claims.

**Revoked coupons still count.** Issuing ten and revoking nine does not restore the allowance.
Otherwise the limit is bypassed by churn and stops measuring anything. The allowance is about
how much authority someone exercises in a week, not the net outcome.

### Per-person overrides

`coupon_allowance_override` raises or lowers the default for one person — a senior agent trusted
with more, someone new who should have less for a fortnight. A **reason is required**, and an
**expiry is strongly recommended**: a temporary raise with no expiry becomes permanent, because
nobody remembers to remove it.

Deliberately data, not a role. "Senior support" as a fourth role means a new permission matrix
for one number. Roles should stay few enough to hold in your head; exceptions belong in a table.

An admin can list every override — a set of exceptions nobody reviews quietly becomes the real
policy.

---

## The request lifecycle

```
                 raise
support ─────────────────┐
salon owner ─────────────┤
                         ▼
                     [pending] ──── 14 days, no decision ───► [expired]
                         │
          ┌──────────────┼──────────────┬────────────────┐
          ▼              ▼              ▼                ▼
     [approved]     [rejected]    [cancelled]      (still pending)
          │          note required   by requester
          ▼
   coupon minted, code returned to the requester
```

**`cancelled` and `rejected` are separate statuses.** One is a withdrawal ("sorted it another
way"), the other is a refusal. Collapsing them makes any approval-rate number meaningless.

### Approve with modification

`value`, `maxDiscountPaise` and `activeTo` can each be overridden at approval. Null means "grant
exactly as asked".

This exists because **an approver who can only say yes or no says no.** "You asked ₹2,000,
here's ₹800" is the answer most of the time, and without it the alternative is reject → re-ask →
re-approve, a round trip nobody makes. In practice they'd just approve the ₹2,000.

### Attribution

The coupon is minted under the **approver's** identity, so its provenance records who authorised
it, not who asked. The request row keeps the link, so *"why does this ₹3,000 coupon exist?"* is
answerable in one hop.

### Required text, with minimums

- **Justification: 20 characters.** An admin decides on this without the requester in the room;
  "needed" tells them nothing. A required field that accepts `.` is required in name only.
- **Rejection note: 10 characters.** A refusal with no reason gets re-asked verbatim tomorrow.

### The queue is oldest-first

Not newest-first. A goodwill request has an unhappy customer already waiting behind it; working
newest-first serves the person who has waited longest last. Newest-first is the natural default
in most lists and the wrong one in every queue.

---

## API

### Salon owner (customer-side token, via the app)

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/coupon-requests` | `SALON_OWNER` |
| GET | `/api/v1/coupon-requests` | `SALON_OWNER`, `MANAGER` — scoped by salon, so a manager sees what their owner asked for |
| POST | `/api/v1/coupon-requests/{id}/cancel` | `SALON_OWNER`, own requests only |

### Staff (via bmp-admin, internal key)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/internal/coupon-requests` | Support raises above their limit |
| GET | `/queue` · `/` · `/by-requester/{id}` · `/pending-count` | Reading |
| POST | `/{id}/approve` · `/{id}/reject` | Deciding |
| GET/PUT/DELETE | `/allowance…` | Admin manages what support has |

**Who may approve is enforced in bmp-admin, not here.** bmp-rewards cannot distinguish an ops
admin from a support agent beyond the role string it is handed — which is exactly why the
internal controller is `ROLE_SERVICE` only and unreachable without the internal key.

---

## The screens

| Who | Where | What |
|---|---|---|
| Admin / ops | Console → **Coupon requests** | The queue, oldest first. Approve (optionally for less), or reject with a reason. |
| Admin / ops | Console → **Coupon allowances** | Own budget as a meter; everyone on a custom allowance, with expiry flagged. |
| Support | Console → **Coupons** | Remaining allowance above the form; when it's gone, a "Raise a request" button rather than a wall. |
| Salon owner | App → owner dashboard → **Offers** | Request a promotion, track it, and copy the code once approved. |

Two pieces of copy that took the most care:

- **"Approved for less than asked"** appears on both the console card and the owner's Offers
  card, with the granted amount leading and the original struck through. An owner who misreads
  this quotes the wrong figure and has the argument at their own counter.
- **The funding question in the owner's language** — "We'll fund this ourselves, take your
  commission on the full price" — rather than `commission_base`. It is the single most
  consequential field on that form and the reason the approval step exists at all.

## Notifications

Two events on the existing outbox → Kafka → `NotificationDispatcher` path.

| Event | Fires when | Goes to |
|---|---|---|
| `coupon_request.raised` | Anyone raises a request | **The ops address** (`BMP_OPS_EMAIL`) |
| `coupon_request.decided` | Approved or rejected | **The requester**, email and/or SMS |

**Published to the outbox inside the same transaction as the request.** If the request rolls
back so does the notification — the alternative sends "your request has been received" for
requests that don't exist.

**Contact details are resolved at RAISE time**, not at decision time, and stored on the row.
Two reasons: the approval transaction also mints a coupon, so an outbound call in there means
bmp-user being briefly down takes the approval with it; and the address you should answer is the
one on file when they asked. bmp-rewards gained its first Feign client for this —
`NotificationDispatcher` deliberately holds none, so whoever emits an event owns putting a real
address in it.

**Ops gets one address, not per-admin routing.** There is no admin distribution list, and
encoding whose problem each request is duplicates the job the queue already does. Unset means
the queue goes unwatched, so the handler logs a warning rather than returning quietly.

**The summary and reason are in the body**, so a decision can be triaged from a phone. *"There
is a request"* is barely more useful than no email; *"support asked for ₹2,500 because a salon
cancelled a wedding booking"* tells you whether it can wait until Monday.

**SMS only on approval, and only with a code.** A rejection needs its reasoning, and reasoning
doesn't fit in 160 characters — a truncated refusal reads worse than no message, because the
recipient now knows they were refused and not why. Email carries that one.

**"You asked ₹2,000, ₹800 was approved" leads the body** when the approver granted less. A
requester who misses it quotes the original figure to a customer.

## Still to do

- **`expireStale()` runs on queue read, not on a schedule.** If nobody opens the queue, nothing
  expires. A cron is a one-liner once anything else here needs scheduling.
- **SMS is still a console stub** platform-wide (DLT registration outstanding), so approval
  texts log rather than send. Email is real once `BMP_EMAIL_PROVIDER=smtp`.
- **No aggregate view of platform coupon spend.** Per-agent allowances exist; "what did BMP give
  away last month" does not.
- **The default numbers are unset in the real sense** — see the warning above.
- **Copying the code is web-only** in the app. React Native removed Clipboard from core and
  `expo-clipboard` is a native dependency for one button; on a phone the code is shown to be
  read aloud, which is what happens at a counter anyway.
