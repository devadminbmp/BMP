-- ═══════════════════════════════════════════════════════════════════════════════════════════
-- V006 — who to tell, and who is standing in front of you.
-- ═══════════════════════════════════════════════════════════════════════════════════════════
--
-- THE PROBLEM THIS SOLVES
-- Until Session 34, bmp-booking emitted no events at all. A customer could book an appointment
-- and receive nothing: no confirmation, no reminder, no notice of their own cancellation. The
-- only message BMP had ever sent them was their login OTP.
--
-- Meanwhile the salon's day view carried `customer_id` and nothing else — a bare UUID. A
-- manager whose stylist has called in sick had no name to apologise to and no number to ring.
--
-- Both wants are the same missing fact: BOOKING DOES NOT KNOW WHO THE CUSTOMER IS. It holds a
-- foreign id into bmp-user and never resolves it.
--
-- WHY SNAPSHOT RATHER THAN LOOK UP EACH TIME
-- Three reasons, in order of how much they matter:
--
--   1. CANCELLATION AND COMPLETION MUST NOT DEPEND ON bmp-user BEING UP. Those paths publish
--      events too, and an outbound call inside `cancel`'s transaction means a customer cannot
--      cancel their appointment because an unrelated service is restarting. Being unable to
--      cancel is a far worse outcome than a slightly stale phone number.
--
--   2. THE SALON DESK IS A HOT READ. It renders every service block for the day; resolving each
--      customer live would be an N+1 across a service boundary on the one screen a manager
--      keeps open all day.
--
--   3. IT MATCHES THE EMITTER-RESOLVES RULE ALREADY IN FORCE. See bmp-rewards'
--      UserServiceClient: NotificationDispatcher deliberately holds no clients, so whoever
--      emits an event is responsible for putting a real address in it. Same reason
--      coupon_request carries requester_phone (V004).
--
-- WHAT THIS IS *NOT*
-- Not a general-purpose copy of the user record. Four fields, resolved once at booking time,
-- for delivery and for saying a name out loud. The moment somebody adds a fifth, this table has
-- started becoming a second and worse bmp-user.
--
-- STALENESS IS ACCEPTED, DELIBERATELY
-- A customer who changes their number after booking gets the message at the old one. That is a
-- real (small) cost and it is the right trade: the alternative is that a service being down
-- prevents cancellations. If this ever bites, the fix is to refresh the snapshot on the
-- reminder job — NOT to make the booking path depend on a live lookup.
--
-- NULLABLE ON PURPOSE
-- Every existing booking has NULL here, and there is no backfill: bmp-booking cannot read
-- bmp-user's tables (locked decision — no cross-schema access), so a backfill would mean a
-- one-off script making thousands of HTTP calls. Every consumer must therefore handle NULL,
-- which they have to anyway — bmp-user can be unreachable at booking time, and Session 34's
-- rule is that the BOOKING STILL SUCCEEDS in that case. See BookingService.resolveCustomer.
-- ═══════════════════════════════════════════════════════════════════════════════════════════

-- What the salon says out loud: "Priya? Your 11 o'clock — I'm so sorry, Meera's off sick."
ALTER TABLE booking_schema.booking ADD COLUMN IF NOT EXISTS customer_name VARCHAR(160);

-- E.164 or the local 10-digit form, exactly as bmp-user holds it. NOT masked here: masking is a
-- presentation decision and belongs at the edge that knows who is asking. Storing it masked
-- would also make it useless for actually sending the SMS, which is its primary job.
ALTER TABLE booking_schema.booking ADD COLUMN IF NOT EXISTS customer_phone VARCHAR(20);

-- Nullable twice over: the column is nullable AND bmp-user's own email is optional (phone is
-- the identity on this platform, email is not). Email is the only channel that works today —
-- SMS is behind DLT registration, which needs the company to exist.
ALTER TABLE booking_schema.booking ADD COLUMN IF NOT EXISTS customer_email VARCHAR(160);

-- The salon's name at the time of booking.
--
-- "Your booking is confirmed" is a message from nobody. "Your booking at Bounce Salon,
-- Indiranagar is confirmed" is the one a customer can act on. Snapshotted for the same reason
-- as price and policy: a salon that rebrands must not retroactively rewrite what a customer was
-- told six months ago.
ALTER TABLE booking_schema.booking ADD COLUMN IF NOT EXISTS salon_name_snapshot VARCHAR(200);

-- ---------------------------------------------------------------------------------------------
-- Deliberately NOT added: an index on customer_phone.
--
-- "Find every booking by this phone number" is a plausible support query and a very effective
-- way to enumerate the platform's customers. If support genuinely needs it, it should go through
-- bmp-admin against bmp-user — where the lookup is audited — not through a convenient index here.
-- ---------------------------------------------------------------------------------------------
