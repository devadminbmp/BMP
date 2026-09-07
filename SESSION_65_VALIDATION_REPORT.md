# Session 65 — Production Readiness Audit: Task-by-Task Report

**Scope:** the 32-task audit spec, backend (`BMP`) + admin console (`BMP-ADMIN`) + apps (`BMP-FE`).
**Non-negotiables checked:** Java 21 · one phone = one salon · hierarchy MAIN ADMIN → ADMIN →
OPERATIONS ADMIN → SUPPORT MANAGER → SUPPORT · backend authorisation mandatory · no frontend-only
security · no mock or temporary fixes.

---

## The authority matrix, computed from source

Not from documentation — parsed out of `StaffPermission.java`, `RoleHierarchy.java` and
`SupportTier.java`, so it is what the code does rather than what a comment claims.

| capability | Main admin | Admin | Ops admin | Support mgr | Support | Finance | Read-only |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| hire below you | ✓ | ✓ | ✓ | ✓ | · | · | · |
| suspend / reset access | ✓ | ✓ | ✓ | · | · | · | · |
| change somebody's role | ✓ | ✓ | ✓ | · | · | · | · |
| edit rota (job title, shift) | ✓ | ✓ | ✓ | ✓ | · | · | · |
| approve leave | ✓ | ✓ | ✓ | ✓ | · | · | · |
| allocate leave plans | ✓ | · | · | · | · | · | · |
| edit a customer account | ✓ | ✓ | ✓ | ✓ | ✓ | · | · |
| **erase** a customer | ✓ | ✓ | ✓ | · | · | · | · |
| edit a salon listing | ✓ | ✓ | ✓ | · | · | · | · |
| freeze a salon | ✓ | ✓ | ✓ | · | · | · | · |
| **delete** a salon | ✓ | · | · | · | · | · | · |
| issue refunds | ✓ | ✓ | · | · | · | ✓ | · |
| platform settings | ✓ | ✓ | ✓ | · | · | · | · |
| configure queues | ✓ | ✓ | ✓ | ✓ | · | · | · |
| reveal customer PII | ✓ | ✓ | ✓ | ✓ | ✓ | · | · |
| **rank** | 50 | 40 | 30 | 20 | 10 | 0 | 0 |
| **support tier** | 4 | 0 | 3 | 2 | 1 | 0 | 0 |
| **permissions held** | 23 | 22 | 20 | 15 | 10 | 6 | 4 |

Rank and tier are deliberately different ladders. ADMIN outranks Ops (40 > 30) and works **no
tickets** (tier 0) — it manages people and money, it does not sit on the desk.

---

## Task-by-task

| # | Task | Outcome |
|---|---|---|
| 0 | Java 21 | **Verified.** `<java.version>21</java.version>`, `maven.compiler.release 21`. |
| 1 | Session survives refresh | **Fixed.** Root cause was Spring Security 6 returning 403 for *unauthenticated* requests; added an `AuthenticationEntryPoint` returning 401. Session hydrate now re-mints from the refresh token first, with a tri-state outcome so "server unreachable" never destroys a session. |
| 2 | Name lost on signup | **Fixed.** |
| 3 | ONE PHONE = ONE SALON | **Enforced in the database.** V027 partial unique index on `salon_staff(user_id) WHERE role='owner'`. The service check remains for the message; the index is what makes it true. |
| 4 | Duplicate salon requests | **Fixed.** Moderation list collapses to latest-per-salon. |
| 5 | Role hierarchy | **Built.** `RoleHierarchy` is now the single definition; `StaffAccountScope`, `LeaveApprovalScope` and `AccountScope` all defer to it (was four hand-maintained copies). |
| 6 | Main Admin as HR | **Built.** V016 `leave_plan` + `leave_entitlement`, Apr–Mar leave year, balances, history, HR page. |
| 7 | ADMIN powers | **Complete.** Verified ADMIN ⊇ OPS; 0 endpoints Ops can reach that Admin cannot. V017 routes Ops escalation to Admin. |
| 8 | OPS powers | **Complete**, plus two holes closed (below). |
| 9 | Support Manager powers | **Built.** New `staff:hire` / `staff:offboard`, deliberately narrower than account control. |
| 10 | Support powers | **Verified.** Ten permissions, none touching the team. Can edit customer contact; cannot approve leave, hire, or configure queues. |
| 11 | Support member CRUD | **Built.** Name/phone/email were write-once; now correctable, with the email at a higher bar. |
| 12 | Customer accounts by tier | **Fixed.** Erasure now consults the authority matrix (see below). |
| 13 | Salon editing + owner | **Fixed.** `ownerUserId` was fetched then dropped; the owner is now reachable from the salon. |
| 14 | Freeze enforcement | **Fixed.** Freeze was decorative on the booking path (see below). |
| 15 | Main-Admin-only delete | **Built**, and a bigger bug found alongside it. |
| 16 | Profile management | **Built.** Self-service password change (current password **+** 2FA code), name/phone. |
| 17 | Permission management | **Built.** Promote/demote with two rank checks. |
| 18 | Apply Leave for every role | **Built.** Balance shown where you apply; the form says who decides. |
| 19 | Leave approval UI | **Built.** Reason mandatory on refusal (server-side too), applicant's balance, cover clash. |
| 20 | Leave entitlement schema | **Verified.** V016 applies cleanly; half-days, carry-forward, over-365 and 12.3-day typos all rejected by CHECK constraints. |
| 21 | Main Admin HR view | **Verified** (delivered in 6). |
| 22 | Leave plans | **Verified** (delivered in 6). Seeded 12 casual / 12 sick / 15 annual as a *starting point*, editable. |
| 23–24 | FE + BE RBAC sweep | **372 endpoints swept. 0 genuine gaps.** One IDOR found and fixed. |
| 25 | Migrations review | **84/85 apply cleanly in order** on real PostgreSQL — first time this has been run end to end. |
| 26 | API consistency | **Clean.** 292 record types; every cross-service wire pair matches field-for-field. |
| 27 | Error handling | **9 silent catches, all benign parse-fallbacks.** Three that hid a *data* problem now log. |
| 28 | Security audit | See findings. |
| 29–31 | Role testing / regression / sweep | Matrix above, computed from source, every assertion passing. |
| 32 | This report | — |

---

## The findings that mattered

**1. A support agent could permanently erase a customer.** `removeAccount` was gated only by
"can you administer a customer account", which support holds. V010's authority matrix has said the
opposite since Session 58 — support and managers are at **zero** for `user.anonymise`. Two copies of
one rule, drifted, and the *looser* copy was the one that ran. `bmp-user` refuses to reactivate an
anonymised row: there is no undo. Now consults the matrix.

**2. Freezing a salon did not stop it trading.** Suspension removed a salon from discovery and
nothing checked the status on the way *into* a booking. A customer with the page open could still
book — and the salon's **own counter desk kept taking walk-ins**, because `createCounter` never
consulted the platform's opinion of the salon. Suspension is how you stop a business trading over an
unresolved complaint, and it carried on taking money BMP would be liable for. Now enforced at the one
point both booking paths funnel through, plus reschedule (your call: existing bookings stand and can
be cancelled, not moved further out).

**3. Freeze and Remove rendered for nobody.** The All Salons screen gated its action column on
`can('SUPER_ADMIN') || can('OPS_ADMIN')` — but `can` takes a *permission*, and those are *role*
names. The expression was false for every role including yours, so the column came back empty. An
empty column looks like a design choice, not a failure. Swept the whole console: 23 real permissions,
those two were the only bad strings.

**4. A Support Manager was locked out of 31 endpoints their own agents could use** — including
`/coupon-requests/mine`, their own raised requests, and raising one at all despite having a ₹2,000
ceiling in the matrix. Same shape as your original complaint, pointing the other way.

**5. A rank-0 hole I introduced in Task 5.** Finance and read-only rank 0, and a plain `actor >
target` made them managed by *everybody*, including a Support Manager. A manager could have
suspended a **Finance Admin** — the role that approves refunds. Rank 0 means "outside the chain",
not "junior to everyone in it". Off-ladder roles now need Ops or above.

**6. `PUT /team/{staffId}` had no rank check at all.** Anyone at Ops could set the **platform
owner's** `exitedOn`, which drops them off the roster entirely — erasing you from the team screen
behind a form labelled "job title".

**7. Push-token release IDOR.** `DELETE /me/push-token` took no principal — any authenticated caller
holding another person's token string could silence their notifications, invisibly. Now scoped;
`bmp-notification` refuses a mismatch.

**8. `bmp_staff.tier` was never set by the constructor.** The column default (1) applied to every
console-created account, so a **finance admin was silently eligible for auto-assigned support
tickets**. V015 repairs the rows; `SupportTier.forRole` stops it recurring. A default is not a rule.

**9. Half-days were charged as full days,** and leave straddling 31 March was charged to neither
year. Both invisible until entitlements existed; both would have understated balances in the
employer's favour, quietly, forever.

**10. Two doors to the same unlock, and the console used the weaker one.** `clearOtpLockout` and
`unlock` both end in `auth.unlock(userId)`. The first applies `AccountScope` (knows *whose* account
it is); the second checked only "do you handle PII", which any support agent passes. So an agent
could unlock a **salon owner's** or an **admin's** account through the door the console actually
calls, while the stronger door — never wired to a button — would have refused them. Both now ask the
same question, keeping their separate audit actions.

**11. Six stale `TODO(backend)` comments claiming endpoints did not exist.** All six existed:
`/users/{id}/unlock`, `/users/{id}/resend-otp`, `/users/{id}/account-health`,
`/bookings/{id}/cancel`, `/admin/refunds`, and `requesterName`. A TODO that is wrong is worse than
no TODO — it sends the next person to build something that is already there. Corrected rather than
deleted: the account-health one is now narrowed to what is *genuinely* still partial (OTP lockout
and email-delivery state come back empty rather than invented, because bmp-auth and
bmp-notification do not expose them yet).

---

## Frontend coverage

136 console-facing endpoints checked against every call in `BMP-ADMIN/src/api`. **No screen is
missing.** The 14 that looked unwired were: 9 service-to-service (`hasRole('SERVICE')`, consumed by
bmp-user and bmp-salon, not the console), 2 internal salon callbacks, 2 reached through a
differently-shaped helper (`advanceDataRequest`, `listAudit`), and 1 genuine orphan — the duplicate
unlock above.

`tsc --noEmit` clean on both `BMP-ADMIN` and `BMP-FE`.

**One real frontend gap remains, and it is small:** the public Contact Us form
(`BMP-FE/src/features/contact/ContactForm.tsx`) has no endpoint to post to — there is no
`/api/v1/contact` anywhere. That TODO is accurate, not stale. The form currently collects a message
and does nothing with it.

---

## Verification performed

- **Migrations:** 84/85 across 9 services applied in order on real PostgreSQL, 164 tables. The one
  failure is my own PostGIS substitution (not installed in the sandbox), not a code defect.
- **Java:** 501 files parse clean (tree-sitter sweep) after every change.
- **TypeScript:** `tsc --noEmit` clean on both `BMP-ADMIN` and `BMP-FE`.
- **Guards:** all 372 endpoints classified; the 24 without `@PreAuthorize` verified as correctly
  gated at the filter chain (login doors, the signature-verified webhook, public discovery).
- **Matrix assertions:** promotion, hire, offboard, suspend and leave-approval rules asserted
  role-by-role against the parsed permission map — all passing.

## What I could NOT verify, and you should

1. **`mvn verify` has never run** — Maven Central is unreachable from here. Everything above is
   parse-level and database-level. **Run it before trusting the compile.**
2. **Nothing ran against your Docker Postgres.** Migrations were verified on a sandbox instance.
3. **No service was started.** Endpoint behaviour is reasoned from source, not exercised.

## Before real customer data exists

- `seed/dev-staff-logins.sql` carries a committed password and TOTP secret. **Rotate and delete
  those accounts.**
- The admin password and TOTP secret generated in an earlier session are in the transcript. Rotate.
- Razorpay keys and the Gmail app password belong only in the gitignored local secrets files.
- Test accounts `9113639755` and `9663831388` still need removing — `tools/reset-test-account.sql`.

## One decision left open

V017 routes Ops → Admin for the three capped money actions. Suspensions and erasures still go
straight to you, because Ops is already unbounded there and rewriting that field would be a diff
with no consequence. Say the word if you want those routed through Admin too.
