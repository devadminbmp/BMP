# Authority, escalation and approvals — the prescribed flow

Session 58. This is the single source of truth for **who may do what, up to how much, and who signs
off above it** — for every gated action, not just coupons.

---

## 1. The organisation

| Tier | Role | In the escalation ladder? |
|---|---|---|
| 4 | `super_admin` — the owner of BMP | Yes, top |
| 3 | `ops_admin` | Yes |
| 2 | `support_lead` | Yes |
| 1 | `support_agent` | Yes, bottom |
| 0 | `finance_admin` | **No** — a different axis |
| 0 | `read_only` (analysts) | **No** — reads, never owns |

**Tier 0 is not "junior".** Finance owns money and analysts own nothing; neither is a rung above or
below support. A refund does not become approvable by being handed to a support lead — it needs
somebody who owns the money. That is why the approver is named by **role**, never computed as
`tier + 1`.

`can_manage_staff` is a per-person flag granted by a super_admin. It is what makes *some* ops
admins able to create support accounts without inventing an `ops_admin_senior` role.

---

## 2. The one mechanism

Every gated action follows the same path. Adding a new one is **a row and an executor**, never a
new flow.

```
  Staff member wants to do X at value V
        │
        ▼
  AuthorityService.check(actionType, role, valuePaise)
        │
        ├── ALLOWED         → do it now
        ├── FORBIDDEN       → refuse; do not offer a request
        └── NEEDS_APPROVAL  → ApprovalRequestService.raise(...)
                                   │
                                   ▼
                        approval_request (pending, addressed to a ROLE)
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
                approve()      reject()      escalate()
                    │              │              │
              executor runs    ends, with     moves UP one
                    │           a reason      step, still
              EXECUTED / FAILED               pending
```

**Escalation never bounces back to the requester.** Bouncing back is how a customer waits two days
for a decision nobody actually intended to refuse.

**Approved ≠ executed.** An approved coupon still has to be created, and that call can fail. A
failure marks the request `failed` **and keeps the approval**, so it can be retried without being
re-approved — and the console sorts those to the top, because somebody has probably already told a
customer they are getting something.

---

## 3. The matrix, as seeded

Read a block downward as the path a request walks. All values in rupees; stored as paise.

### `coupon.issue` — the worked example

| Role | May issue alone | Above that → |
|---|---|---|
| `support_agent` | up to **₹500** | support lead |
| `support_lead` | up to **₹2,000** | ops |
| `ops_admin` | up to **₹10,000** | owner |
| `super_admin` | no limit | — |

Requires a linked ticket for support and lead. Goodwill with no complaint behind it is what turns a
support desk into a leak.

### `refund.issue` — the sideways jump

| Role | May issue alone | Above that → |
|---|---|---|
| `support_agent` | **never** (₹0) | finance |
| `support_lead` | **never** (₹0) | finance |
| `finance_admin` | up to **₹10,000** | ops |
| `ops_admin` | up to **₹50,000** | owner |
| `super_admin` | no limit | — |

Support raises a refund and never issues one. Real money leaving the business, and the person
comforting an upset customer is the worst-placed person to decide it.

### `wallet.credit` — tighter than coupons

| Role | Alone | Above → |
|---|---|---|
| `support_agent` | ₹200 | lead |
| `support_lead` | ₹1,000 | ops |
| `ops_admin` | ₹10,000 | owner |
| `super_admin` | no limit | — |

Tighter because wallet credit spends anywhere with no minimum and no expiry pressure — closer to
cash than a discount code is.

### `booking.waive_fee`

| Role | Alone | Above → |
|---|---|---|
| `support_agent` | ₹300 | lead |
| `support_lead` | ₹1,500 | ops |
| `ops_admin` | no limit | — |

Support can waive a small fee outright: it is the commonest gesture, and routing it upward would
queue a ₹100 decision behind a ₹50,000 one.

### Non-monetary — same machinery, zero values

| Action | support / lead | ops_admin | Notes |
|---|---|---|---|
| `salon.suspend` | never → ops | decides | Stops a business's income |
| `stylist.suspend` | never → ops | decides | Same reasoning |
| `user.anonymise` | never → ops | decides | Irreversible; no undo |
| `commission.adjust` | not permitted at all | → owner | Support renegotiating a contract to end a conversation is not a thing |

---

## 4. Where each piece lives

| Concern | Backend | Frontend |
|---|---|---|
| The matrix | `admin_schema.authority_limit` (V010) | `GET /admin/approvals/my-authority` → **My limits** tab |
| "May I?" | `AuthorityService.check` | `checkAuthority()` as the amount is typed |
| Raise | `ApprovalRequestService.raise` | `raiseApproval()` |
| Approver queue | `queueFor(role)`, `idx_approval_queue` | **Waiting on me** tab |
| Decide | `approve` / `reject` / `escalate` | Three buttons on the review card |
| Carry out | `ApprovalActionExecutor` per action | — |
| Trail | `approval_decision` (append-only) | "Already seen by" on the review card |

**Console route:** `/support/approvals`, guarded on `ticket:view` — everyone on the ladder needs it,
and the page renders the right tabs from the caller's own authority.

---

## 5. Adding a new gated action

1. Insert rows into `authority_limit` — one per role that should have any authority, including
   `0` rows for roles that may only *request*.
2. Write a class implementing `ApprovalActionExecutor` with a matching `actionType()`.
3. Add a label to `ACTION_LABELS` in `BMP-ADMIN/src/api/approvals.ts`.

Nothing else. Not the controller, the queue, the audit trail or the console.

**Make the executor idempotent.** Execution is retried after a failure, so an executor that issues
two coupons when run twice turns a transient network error into money lost. `CouponIssueExecutor`
derives the coupon code from the request id for exactly this reason.

---

## 6. Deliberate failure modes

- **No row = FORBIDDEN**, never "unlimited". A new action added without matrix rows is impossible
  for everyone below the owner, rather than accidentally available to the whole desk.
- **`0` ≠ absent.** `0` means "may request, may never perform" — support's refund band. Without the
  distinction, "cannot approve" and "not configured" would read identically.
- **No approver above = refuse, don't queue.** Pretending to escalate at the top of a path leaves a
  request pending forever, addressed to a role that will never look at it.
- **Raising something you could do yourself is a 409.** An approver whose queue fills with
  busywork stops reading it, and then misses the one that mattered.

---

## 7. Verification

Simulated against a real PostgreSQL with the seeded matrix:

- 16 authority decisions — including support's ₹800 coupon routing to a lead, any refund routing to
  finance, finance's own ₹20,000 routing to ops, and finance being **forbidden** from issuing
  coupons at all.
- The full example end to end: raised → lead escalates → ops approves → executed, with the trail
  keeping *both* the escalation and the approval.
- Constraint guards: an executed row with no decider, a three-character justification, an unknown
  status and an unknown decision kind are all refused by the database.

`mvn verify` has never run in the assistant's sandbox — Maven Central is unreachable there. javac in
IntelliJ remains the real check.

---

## 8. Session 59 — the owner sets the numbers, and nothing exceeds what was paid

Four changes, all from one instruction: *"all ranges can be fixed by admin, owner of BMP… next
support or anyone can't give coupon price more than what customer had booked… next all refund
coupons etc all details should be displayed for admin and finance admin."*

### 8.1 The bands are data, not constants

`PUT /api/v1/admin/approvals/matrix` — **SUPER_ADMIN only**, audited as
`AUTHORITY_LIMIT_CHANGED`. `GET /matrix` is readable by ops and finance too, because knowing where
a ceiling sits is how a lead decides whether to approve or pass up, and hiding the rule somebody is
judged against helps nobody.

Ops cannot edit. An ops admin who can raise their own ceiling does not have one, and the matrix
becomes advisory the moment anyone inconvenienced by it can change it — the same reasoning as a
salon owner not setting their own commission (Session 45).

One invariant, enforced in `AuthorityService.updateLimit`: **a band can never exceed the band it
escalates to.** Otherwise passing a request up would make approval *less* likely, and the ladder
would stop meaning anything.

The console renders the whole matrix as one grid (`ApprovalsPage` → "Everyone's limits"), because
₹2,000 for a lead is only meaningful next to ₹500 for an agent and ₹10,000 for ops. Editing bands on
separate screens is how somebody sets a lead above ops without noticing.

### 8.2 Never more than the customer paid

`GoodwillCapService` applies to `coupon.issue`, `refund.issue`, `wallet.credit` and
`booking.waive_fee` — an **allow-list**, so a new action is uncapped only if somebody deliberately
left it out.

```
remaining = booking.final_amount − booking.total_refunded − Σ goodwill_grant(booking)
```

Checked in three places, and all three are load-bearing:

| Where | Why it cannot be dropped |
|---|---|
| Raising an approval | Stops nonsense reaching an approver's queue |
| Executing an approval | Monday's amount can be outside the cap by Thursday |
| The direct coupon path | Goodwill inside somebody's own band **never becomes an approval at all** |

It applies to **every** role including `super_admin`. "More than the customer paid" is not a
seniority question.

**bmp-booking unreachable ⇒ refuse (503), never allow.** Money leaving on an unverified amount is
permanent, and failing open would make the cap advisory during exactly the incidents that generate
the most goodwill requests.

#### V012 `goodwill_grant` — the half the cap could not previously see

Before V012 the subtraction used refunds only. A ₹600 booking accepted a ₹500 coupon and then a
second ₹500 coupon, because a coupon never touches `total_refunded`. The rule was enforced against
one kind of goodwill and silently not the others, which is worse than no rule because it looked
enforced.

`admin_schema.goodwill_grant` records every non-refund gesture at the moment it is **granted**, not
approved — the majority are inside somebody's own band and produce no approval row.

- `CHECK (action_type <> 'refund.issue')` — refunds are authoritative on the booking; recording
  them here too would double-count and halve every customer's real ceiling. A constraint, not a
  comment, because "remember not to insert refunds" survives about six months.
- `UNIQUE (approval_request_id) WHERE NOT NULL` — an approved request that failed keeps its
  approval and can be retried. Without this, one ₹400 coupon retried once would consume ₹800.
- `record(...)` is called **after** the gesture succeeds and **never throws**: the customer already
  has the coupon, and a false failure would have an agent issue it twice. A missed row loosens one
  future cap; a duplicate is real money. The former is logged loudly as the lesser harm.

### 8.3 One place to watch it all

`GET /api/v1/admin/goodwill` (+ `/summary`, `/failed`) — **SUPER_ADMIN and FINANCE_ADMIN**. Not
ops, and deliberately not leads: it is a financial view, and per-agent totals are performance data.

The ledger merges **both** sources — approval requests and within-authority grants — filtering
grants that carry an approval id so an escalated coupon is not counted twice. A ledger built from
approvals alone would show every escalated gesture and none of the routine ones, which is backwards:
the routine ones are the volume, and volume is what a monthly total is made of.

Three figures, never summed into one:

| | Meaning |
|---|---|
| **Given** | Executed. Money that actually left. |
| **Awaiting a decision** | Exposure. Not spent. |
| **Failed to deliver** | Approved and never arrived — a **promise outstanding**, not a cost. |

Failed rows surface at the top of the screen and have their own endpoint, which **ops can also
read**: chasing an undelivered promise is not a financial report.

Per-agent totals are capped at ten rows and rendered as a plain list, never a ranked chart. An agent
who knows they are being charted refuses reasonable goodwill to protect their figure, and the churn
costs more than the goodwill did.

### 8.4 Queue assignment — auto, manual, or an analyst

`admin_schema.queue_config`, one row per tier (V011). All three modes run through the single
`TicketAssignmentService.autoAssign`, not three code paths — the parts that are easy to get wrong
(keeping `open_ticket_count` honest, never assigning to somebody on leave, never crossing tiers)
must not be reimplemented per mode.

| Mode | Behaviour | Its failure, stated in the UI |
|---|---|---|
| `auto` | Least-loaded at that tier | — |
| `manual` | Nothing assigned; a pool | Easy tickets get cherry-picked — watch the oldest item |
| `analyst` | One named person distributes | If they are away, tickets wait in the pool |

`max_open_per_agent` (0 = uncapped): above the cap, new tickets stay in the pool rather than piling
onto somebody already full. An overloaded desk becomes visible instead of quietly slow.

`CHECK (assignment_mode <> 'analyst' OR analyst_staff_id IS NOT NULL)` — analyst mode with nobody
named would silently degrade to no assignment at all.

### 8.5 Verification

Against a real PostgreSQL:

- **V011** — 12 constraint cases: half-day across a range refused, end-before-start refused,
  approved-with-no-decider refused, analyst-mode-with-nobody-named refused, exit-before-join
  refused.
- **V012** — refund rows refused, zero and negative grants refused, two within-authority grants sum
  correctly, the same approval recorded twice refused, a second null-approval grant still accepted,
  and the resulting ceiling arithmetic refuses a further coupon that the old refunds-only rule would
  have allowed.
- **13 goodwill-cap cases**, including the double-dip (refund then coupon) and "bmp-booking
  unreachable must NOT fail open".
- 457 Java files parse, 0 errors. Console `tsc --noEmit` clean apart from the two pre-existing
  `vite.config.ts` Node-types errors.

`mvn verify` still has never run in the assistant's sandbox. javac in IntelliJ remains the real
check.
