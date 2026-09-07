-- ════════════════════════════════════════════════════════════════════════════════════════════════
-- V027 — ONE PHONE = ONE SALON, enforced by the database. Session 65.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── WHAT ALREADY EXISTED, AND WHY IT WAS NOT ENOUGH ────────────────────────────────────────────
-- SalonService.create has refused a second salon since Session 48:
--
--     if (staffService.ownsASalon(ownerUserId)) throw SALON_ALREADY_EXISTS
--
-- That is a CHECK-THEN-ACT. It reads, decides, and then writes, with nothing holding the two
-- together — and it is the classic shape that fails in three ways:
--
--   1. CONCURRENCY. Two requests arriving together both read "no salon", both pass, both insert.
--      Rare by hand, certain under a retry or a double-tapped button.
--   2. ANY OTHER WRITER. A seat deleted directly in SQL frees the owner to create a second salon
--      while the first still exists. tools/reset-test-account.sql does exactly this — it removes
--      salon_staff rows and leaves salon rows behind. My own tool can produce the state the
--      service is trying to prevent.
--   3. A FUTURE CALLER. The next endpoint that inserts an owner seat has to remember to check.
--      A constraint cannot be forgotten.
--
-- The service check STAYS. It is what produces the useful message ("resubmit rather than creating
-- a new one"); this index is what makes the rule true. Application checks are for explaining,
-- constraints are for guaranteeing, and a system needs both.
--
-- ── WHY user_id AND NOT phone ──────────────────────────────────────────────────────────────────
-- "One phone = one salon" is the business rule; the phone lives in user_schema.users, which is a
-- different service's table. Enforcing it there would mean a cross-schema constraint, which
-- Postgres cannot express and which would couple two services' deploy order.
--
-- It does not need to. The phone is already UNIQUE on users (uk_users_phone, V004) — one phone is
-- one user. So "one owner seat per user" IS "one salon per phone", by transitivity, and it is
-- enforceable in the table that actually holds the seat.
--
-- ── WHY A PARTIAL INDEX ────────────────────────────────────────────────────────────────────────
-- Only OWNER seats are constrained. A person can legitimately be a MANAGER at one salon and a
-- STYLIST at another; the rule is about OWNING. Indexing every row would forbid that and would
-- break the manager invite flow.
--
-- lower(role) because the column is free text: 'OWNER' is what addOwner writes today and
-- ownsASalon compares case-insensitively. Indexing the raw value would let 'Owner' slip past.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

/*
 * FAIL LOUDLY, WITH THE ANSWER, IF DUPLICATES ALREADY EXIST.
 *
 * A bare CREATE UNIQUE INDEX on dirty data fails with "could not create unique index ... Key
 * (user_id)=(...) is duplicated" — true, and useless: it names one pair and stops, the service
 * will not start, and nobody knows how many there are or which to keep.
 *
 * This raises an exception naming every offender and what to do. The migration still fails and
 * the service still will not start — which is CORRECT, because silently picking a salon to
 * disown would destroy somebody's business record to make a migration pass. A human decides.
 *
 * Run tools/one-salon-per-owner-doctor.sql BEFORE restarting to see this without the downtime.
 */
DO $$
DECLARE
    offenders text;
    offender_count int;
BEGIN
    SELECT count(*), string_agg(t.user_id::text || ' owns ' || t.n || ' salons', E'\n  ')
      INTO offender_count, offenders
      FROM (
        SELECT user_id, count(*) AS n
          FROM salon_schema.salon_staff
         WHERE lower(role) = 'owner'
         GROUP BY user_id
        HAVING count(*) > 1
      ) t;

    IF offender_count > 0 THEN
        RAISE EXCEPTION E'Cannot enforce one-salon-per-owner: % owner(s) already hold more than one salon.\n  %\n\nNothing has been changed. Decide which salon each owner keeps, remove the other OWNER seat from salon_schema.salon_staff, then restart. See tools/one-salon-per-owner-doctor.sql.',
            offender_count, offenders;
    END IF;
END $$;

/*
 * The guarantee itself.
 *
 * NOT "one owner per salon" — that is a different rule and this does not express it. A salon with
 * two owner seats stays legal here; what cannot happen is one PERSON holding two.
 */
CREATE UNIQUE INDEX IF NOT EXISTS uk_salon_staff_one_owner_seat
    ON salon_schema.salon_staff (user_id)
    WHERE lower(role) = 'owner';

COMMENT ON INDEX salon_schema.uk_salon_staff_one_owner_seat IS
    'ONE PHONE = ONE SALON (Session 65). Phone is unique on users, so one owner seat per user is '
    'one salon per phone. Partial: a person may still be a manager or stylist elsewhere.';
