-- ════════════════════════════════════════════════════════════════════════════════════════════════
--  V006 — the referral programme, set by an admin rather than by a config file. Session 64.
-- ════════════════════════════════════════════════════════════════════════════════════════════════
--
--  WHAT CHANGES
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  Reward amounts lived in `bmp.rewards.referrer-reward-paise` / `referee-reward-paise` — Spring
--  properties, which means changing what the platform pays required an env var, a redeploy, and a
--  developer. A commercial lever that only engineering can pull is a commercial lever nobody pulls.
--
--  This makes it an admin decision: both amounts, and each side independently switchable off, from
--  the console, taking effect on the next referral.
--
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  APPEND-ONLY VERSIONS, NOT AN EDITABLE ROW — AND THIS IS THE IMPORTANT PART
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  Every change INSERTS a new version. Nothing is ever updated in place.
--
--  Because a referral is a PROMISE. Somebody told their friend about us under a ₹150 offer; when
--  that friend finally books three weeks later, they are owed ₹150 — even if the rate is ₹50 by
--  then, and even if the programme has been switched off entirely in the meantime.
--
--  `referral.referrer_reward_paise` / `referee_reward_paise` (V002) already freeze the amounts onto
--  the referral row at attribution, so honouring the old promise costs nothing extra at payout: the
--  answer is already on the row. This table only decides what gets frozen NEXT.
--
--  The versions also answer "why did we pay that?" months later. A single mutable settings row
--  cannot: it shows today's number and no way to know what yesterday's was.
--
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  HOW "DISABLE ONE SIDE" WORKS, WITHOUT A NEW COLUMN ON referral
--  ──────────────────────────────────────────────────────────────────────────────────────────────
--  Switching a side off freezes ZERO onto that side of the next referral, and the payout skips a
--  zero credit. So "the person who refers still gets paid, the person referred no longer does" is
--  expressible with the columns that already exist, and a referral row remains a complete record of
--  what was promised without needing to consult this table to interpret it.
--
--  The alternative — storing enabled/disabled on the referral and checking it at payout — would
--  mean a row whose amount says ₹100 and whose true value is nothing. Two fields that must be read
--  together to mean anything is how a payout bug happens.
-- ════════════════════════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS rewards_schema.referral_program (
    id                     UUID PRIMARY KEY NOT NULL,          -- UUIDv7

    /*
     * What each side gets, in paise. Integer paise, never a decimal — the platform-wide money rule.
     *
     * Zero is legal and meaningful: it is how a side is switched off (see the header). The CHECK
     * forbids negatives, which would be a debit dressed as a reward.
     */
    referrer_reward_paise  BIGINT NOT NULL,
    referee_reward_paise   BIGINT NOT NULL,

    /*
     * Redundant with `reward_paise = 0`, and kept anyway.
     *
     * "Switched off" and "set to zero" are the same to the payout and different to a human: an
     * admin who disabled the referee side wants to see it come back at the old amount when they
     * re-enable it, not have to remember what it was. Storing the intent separately from the
     * amount is what makes that possible.
     *
     * The service is responsible for freezing zero when a side is disabled — the two are read
     * together HERE, and only here, so the referral row stays unambiguous.
     */
    referrer_enabled       BOOLEAN NOT NULL DEFAULT true,
    referee_enabled        BOOLEAN NOT NULL DEFAULT true,

    -- When this version starts applying. Normally now(); a future value schedules a change.
    effective_from         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Who changed it, and why. `note` is required by the service, not the schema: an unexplained
    -- change to what the platform pays is the one somebody has to reconstruct from bank statements.
    changed_by_staff_id    UUID,
    changed_by_email       VARCHAR(160),
    note                   TEXT,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_referral_program_non_negative
        CHECK (referrer_reward_paise >= 0 AND referee_reward_paise >= 0),

    /*
     * A ceiling, in the schema rather than only in the UI.
     *
     * ₹10,000 per side. Not a business rule — a typo guard: an admin meaning ₹150 who types the
     * amount in paise-by-mistake enters 15000 and gets ₹150; one who fat-fingers an extra two zeros
     * would otherwise commit the platform to ₹15,000 per referral, and the first anyone knows is
     * the payout. A constraint here catches it before the version is written.
     */
    CONSTRAINT chk_referral_program_sane_ceiling
        CHECK (referrer_reward_paise <= 1000000 AND referee_reward_paise <= 1000000)
);

/*
 * The hot path: "what is the programme right now" — newest effective version at or before now.
 * Read on every attribution, so it is worth an index even though the table stays small.
 */
CREATE INDEX IF NOT EXISTS idx_referral_program_effective
    ON rewards_schema.referral_program (effective_from DESC);

/*
 * Seed version one from the properties this replaces, so behaviour does not change on deploy.
 *
 * 15000 / 10000 paise = ₹150 referrer, ₹100 referee — the defaults in ReferralService's @Value
 * annotations. A migration that silently altered what the platform pays would be a bad way to find
 * out this table exists.
 *
 * Guarded so a re-run cannot stack duplicate "initial" rows.
 */
INSERT INTO rewards_schema.referral_program
    (id, referrer_reward_paise, referee_reward_paise, referrer_enabled, referee_enabled,
     effective_from, changed_by_email, note)
SELECT gen_random_uuid(), 15000, 10000, true, true, now(), 'system',
       'Initial version, migrated from bmp.rewards.*-reward-paise properties. Unchanged behaviour.'
WHERE NOT EXISTS (SELECT 1 FROM rewards_schema.referral_program);

COMMENT ON TABLE rewards_schema.referral_program IS
    'Append-only versions of the referral offer. A change applies to FUTURE referrals only — see V006 header.';
COMMENT ON COLUMN rewards_schema.referral_program.referee_enabled IS
    'Switching a side off freezes ZERO onto that side of the next referral; the payout skips zero credits.';
