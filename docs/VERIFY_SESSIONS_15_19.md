# Backend verification checklist — Sessions 15–19

**Why this exists:** the Cowork sandbox has no JDK or Maven and no network access to Maven
Central, so none of the Java written in Sessions 15–19 has ever been compiled. Every file was
written against the surrounding code and reviewed by hand, but "reviewed" is not "compiles", and
certainly not "works". Run this before merging.

Work in order — later steps assume earlier ones passed.

---

## 1. Compile

```bash
cd "C:\BMP -PRJ\BMP"
mvn -q -T1C clean compile
```

If you'd rather isolate failures, the modules that changed are:

```bash
mvn -q -pl bmp-common,bmp-salon,bmp-booking,bmp-auth -am clean compile
```

### Most likely failures, and what they mean

| Symptom | Where | Why |
|---|---|---|
| `constructor StylistAvailability ... cannot be applied` | bmp-salon | `dayOfWeek` changed `int` → `Integer` (Session 18). Autoboxing should handle existing callers; if a call site passes a primitive in a generic context it may need an explicit cast. |
| `incompatible types: int cannot be converted to Integer` | AvailabilityService | Same change, on the read side. |
| `cannot find symbol: method setItemStatus` | bmp-booking | Added to `BookingServiceItem` in Session 16 — check the file saved. |
| `constructor StylistSalonResponse ... cannot be applied` | bmp-salon | Gained `stylistName` (Session 16). Only one call site existed; if a teammate added another it needs the new argument. |
| `constructor ConsumeInviteResponse` / `ConsumeInviteRequest` | bmp-auth **and** bmp-salon | Both gained fields (Session 17). The compact constructors should keep old call sites working — if not, the mirror DTOs on the two sides have drifted. |
| `cannot find symbol: class UserServiceClient` | bmp-salon | New Feign client (Session 15) at `com.bmp.salon.client.UserServiceClient`. |
| `NoSuchBeanDefinition: StylistSalonRepository` in `StaffService` | bmp-salon | Constructor gained a parameter (Session 17). |

---

## 2. Flyway migrations

```bash
mvn -q -pl bmp-salon flyway:info    # or start the service and read the log
```

**Check:** `V008__staff_invites_role.sql` is the highest version in
`bmp-salon/src/main/resources/db/migration/` and no teammate has also created a V008. Two files
with the same version is a hard Flyway failure and the most likely merge conflict on this branch.

### Run V008 against a database that already has data

This is the one that matters — a fresh `docker compose down -v` proves nothing about an upgrade.

```sql
-- before
SELECT id, phone, status FROM salon_schema.staff_invites;
```

Apply the migration, then:

```sql
-- after: every pre-existing row must read role='manager', invitee_name NULL
SELECT id, phone, status, role, invitee_name FROM salon_schema.staff_invites;
```

If any pre-existing row has a NULL or unexpected `role`, the `DEFAULT 'manager'` didn't apply
and old invites will break on redemption.

---

## 3. Smoke-test the endpoints added in these sessions

Get a token first (`POST /api/v1/auth/otp/request` then `/verify`; dev OTP is `000000`).

### Session 15 — owner team management
- [ ] `GET /api/v1/salons/{salonId}/staff` as the owner → own roster, names populated from bmp-user
- [ ] Same call with **another salon's** id → `403`
- [ ] `POST .../staff` with a phone that has no account → `404 NO_BMP_ACCOUNT_FOR_PHONE`
- [ ] `POST .../staff` with an existing customer → `201`, and **check `users.default_role` flipped to `manager`**. If it didn't, that person's next token still says `customer` and every manager screen will 403.
- [ ] `DELETE .../staff/{staffId}` for the OWNER seat → `409`
- [ ] After removing a manager, their next `/auth/refresh` returns `salonId: null`

### Session 16 — booking authorization + manager desk
- [ ] **`GET /api/v1/bookings/{id}` for someone else's booking → `403`.** Before this session it returned the booking. This is the security fix; test it explicitly.
- [ ] `GET /api/v1/bookings/salon/day` as a manager → own salon's day only
- [ ] Same endpoint with **no token** → `401`. bmp-booking's `public-paths` was `/**` until this session, so if this returns 200 the config change didn't take.
- [ ] `POST /api/v1/bookings/{id}/arrive` on a PENDING booking → `409` (expected — see §5)

### Session 17 — stylist invites
- [ ] `POST .../invites` with `role: stylist` as a **manager** → `201`
- [ ] `POST .../invites` with `role: manager` as a **manager** → `403 ONLY_OWNER_CAN_INVITE_MANAGERS`
- [ ] Redeem a stylist invite at signup → `stylist_salon` row created, and the session's `salonId` is **null** (portable identity — if it's populated, `resolveSalonScope` is wrong)

### Session 18 — availability
- [ ] `PUT .../availability/weekly` with Monday hours → rows written with `day_of_week` = **1** (Sunday=0 convention; if Monday lands on 0 the writer and reader disagree and every stylist's hours are on the wrong day)
- [ ] `GET /api/v1/availability/slots` for that stylist now returns slots — **this is the real proof**, since availability existed only to feed the algorithm
- [ ] `POST .../time-off` on a date with an existing booking → `409` naming the conflicts
- [ ] Same call with `force: true` → `201`
- [ ] Overlapping working windows → `400 OVERLAPPING_WORKING_HOURS`
- [ ] A break outside working hours → `400 BREAK_OUTSIDE_WORKING_HOURS`
- [ ] **Read back a full-day leave row** (null `start_time`, null `day_of_week`) — this is the exact case the `int` → `Integer` fix addresses. If it throws, the entity change didn't save.

---

## 4. Frontend

```bash
cd "C:\BMP -PRJ\BMP-FE"
npm run typecheck     # passing as of Session 19
npm run lint
npx expo start        # then check /discover, /privacy, /terms, and sign in as a manager
```

Set `EXPO_PUBLIC_USE_MOCKS=false` in `.env` to point at the real backend.

---

## 5. Known-expected failures (not bugs)

- **Every booking sits in `PENDING`.** `PENDING → CONFIRMED` is a SYSTEM transition from the
  Razorpay webhook (Phase 3). So `/arrive`, `/start`, `/complete` and `/no-show` all return 409
  in a real environment today. Mock mode seeds CONFIRMED bookings so the UI can be exercised.
- **OTP arrives by email only.** SMS needs Indian DLT registration.
- **Invite codes are not delivered** by the backend — the issuer shares them by hand, same
  reason.

---

## 6. If something here fails

Tell me the exact compiler or runtime error and I'll fix it. These are almost certainly small
signature mismatches rather than design problems — but they're real, and I'd rather you hit them
here than in front of a salon.
