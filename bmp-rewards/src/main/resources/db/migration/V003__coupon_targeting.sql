-- V003 — coupon targeting, provenance, and the support/admin distinction.
--
-- ============================================================================================
-- BUILDING ON WHAT'S THERE, NOT BESIDE IT
-- ============================================================================================
-- V002 already created rewards_schema.coupon with the parts that are genuinely hard: discount
-- type and value, min spend, per-user and total caps, an active window, wallet stacking, and —
-- importantly — `commission_base`, which decides whether the salon's commission is calculated
-- before or after the discount. That column is the funding question ("who pays for this?")
-- already answered, and nothing here disturbs it.
--
-- What V002 could not express is WHO a coupon is for. `salon_id` is one salon or all salons,
-- and there is no notion of a specific list of users. That's what this migration adds, along
-- with the provenance needed to answer "who created this and why".
--
-- ============================================================================================
-- WHY PROVENANCE MATTERS MORE THAN IT LOOKS
-- ============================================================================================
-- Coupons are money. A support agent handing out ₹300 apologies all day is a real cost, and the
-- only way to see it is to record who issued each one and which complaint it settled. So
-- support-issued coupons REQUIRE a ticket reference, enforced in the service layer — not to
-- distrust anyone, but because "what did we spend on goodwill last month, and on what" should
-- be a query rather than a guess.
-- ============================================================================================

-- --------------------------------------------------------------------------------------------
-- 1. Targeting
-- --------------------------------------------------------------------------------------------

-- all_users | selected_users | new_users | referred_users
--
-- Note new_users and referred_users overlap with V002's `coupon_type` (welcome/referral), which
-- describes what a coupon IS. This describes who can USE it. They're related but not the same:
-- a festival coupon restricted to new users is type=festival, audience=new_users.
ALTER TABLE rewards_schema.coupon
    ADD COLUMN IF NOT EXISTS audience_type VARCHAR(20) NOT NULL DEFAULT 'all_users';

-- all_salons | selected_salons
--
-- V002's nullable salon_id stays and still means "this one salon" for existing rows. When
-- salon_scope = 'selected_salons' the list lives in coupon_salon below, because "these four
-- salons in Indiranagar" is a normal campaign and a single column cannot hold it.
ALTER TABLE rewards_schema.coupon
    ADD COLUMN IF NOT EXISTS salon_scope VARCHAR(20) NOT NULL DEFAULT 'all_salons';

-- A human name. `code` is what customers type (BMPWELCOME200); this is what staff recognise in
-- a list six weeks later ("Diwali — Indiranagar salons").
ALTER TABLE rewards_schema.coupon ADD COLUMN IF NOT EXISTS name VARCHAR(120);
ALTER TABLE rewards_schema.coupon ADD COLUMN IF NOT EXISTS description TEXT;

-- draft | active | paused | expired | revoked
--
-- Separate from the active_from/active_to window on purpose: a campaign that's live but going
-- wrong must be stoppable NOW, without editing dates and without deleting a coupon customers
-- may already hold. 'paused' is that button.
ALTER TABLE rewards_schema.coupon
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'active';

-- A percentage coupon with no ceiling is how a ₹8,000 bridal package becomes free. Nullable
-- because it's meaningless for flat-value coupons; the service requires it for percent ones.
ALTER TABLE rewards_schema.coupon ADD COLUMN IF NOT EXISTS max_discount_paise BIGINT;

-- --------------------------------------------------------------------------------------------
-- 2. Provenance — who created this, and why
-- --------------------------------------------------------------------------------------------

ALTER TABLE rewards_schema.coupon ADD COLUMN IF NOT EXISTS created_by_staff_id UUID;
-- Denormalised so the row stays readable after that person leaves.
ALTER TABLE rewards_schema.coupon ADD COLUMN IF NOT EXISTS created_by_email VARCHAR(160);
-- The role AT THE TIME — which is what makes "support issued this" auditable later.
ALTER TABLE rewards_schema.coupon ADD COLUMN IF NOT EXISTS created_by_role VARCHAR(20);

-- REQUIRED for support-issued coupons (enforced in CouponAdminService, not by a CHECK, because
-- existing rows predate this and a failing migration on a live database is the worse outcome).
-- Admin-issued campaign coupons leave it null.
ALTER TABLE rewards_schema.coupon ADD COLUMN IF NOT EXISTS issued_for_ticket_id UUID;
ALTER TABLE rewards_schema.coupon ADD COLUMN IF NOT EXISTS issue_reason TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_coupon_code ON rewards_schema.coupon(upper(code));
CREATE INDEX IF NOT EXISTS idx_coupon_status ON rewards_schema.coupon(status, active_to);
CREATE INDEX IF NOT EXISTS idx_coupon_creator ON rewards_schema.coupon(created_by_staff_id, created_at DESC);

-- --------------------------------------------------------------------------------------------
-- 3. Who exactly it's for
-- --------------------------------------------------------------------------------------------

-- Only populated when audience_type = 'selected_users'.
--
-- This is the table support writes to: a goodwill coupon is for ONE person, and it must not be
-- usable by anyone who happens to learn the code. Redemption checks membership here, so a
-- leaked code is worthless to a stranger.
CREATE TABLE IF NOT EXISTS rewards_schema.coupon_user (
    id UUID PRIMARY KEY NOT NULL,
    coupon_id UUID NOT NULL,
    user_id UUID NOT NULL,          -- logical ref -> user_schema.users
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_coupon_user ON rewards_schema.coupon_user(coupon_id, user_id);
CREATE INDEX IF NOT EXISTS idx_coupon_user_user ON rewards_schema.coupon_user(user_id);
ALTER TABLE rewards_schema.coupon_user
    ADD CONSTRAINT fk_coupon_user_coupon FOREIGN KEY (coupon_id) REFERENCES rewards_schema.coupon(id);

-- Only populated when salon_scope = 'selected_salons'.
CREATE TABLE IF NOT EXISTS rewards_schema.coupon_salon (
    id UUID PRIMARY KEY NOT NULL,
    coupon_id UUID NOT NULL,
    salon_id UUID NOT NULL,         -- logical ref -> salon_schema.salon
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_coupon_salon ON rewards_schema.coupon_salon(coupon_id, salon_id);
ALTER TABLE rewards_schema.coupon_salon
    ADD CONSTRAINT fk_coupon_salon_coupon FOREIGN KEY (coupon_id) REFERENCES rewards_schema.coupon(id);

-- --------------------------------------------------------------------------------------------
-- 4. The limits on what support may issue
-- --------------------------------------------------------------------------------------------
--
-- Configuration, not constants in code, so the numbers can be raised at 9am on a bad day
-- without a deploy — and so changing them is itself an audited action.
--
-- These are STARTING VALUES chosen to be obviously conservative. Set them deliberately before
-- launch; they are not a considered business position.

CREATE TABLE IF NOT EXISTS rewards_schema.coupon_policy (
    id UUID PRIMARY KEY NOT NULL,
    policy_key VARCHAR(60) NOT NULL,
    policy_value TEXT NOT NULL,
    description TEXT,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_coupon_policy_key ON rewards_schema.coupon_policy(policy_key);

INSERT INTO rewards_schema.coupon_policy (id, policy_key, policy_value, description, updated_at) VALUES
  ('01930000-0000-7000-8000-000000000101', 'support_max_flat_paise', '50000', 'Largest flat-value goodwill coupon a support agent may issue without an admin. 50000 paise = Rs 500. TODO(pre-launch): set deliberately.', NOW()),
  ('01930000-0000-7000-8000-000000000102', 'support_max_percent_bps', '2000', 'Largest percentage a support agent may issue, in basis points. 2000 = 20%.', NOW()),
  ('01930000-0000-7000-8000-000000000103', 'support_max_validity_days', '30', 'How long a support-issued coupon may last. Goodwill should be used soon or not at all.', NOW()),
  ('01930000-0000-7000-8000-000000000104', 'support_max_recipients', '1', 'How many users one support-issued coupon may target. 1 = the person who complained, and nobody else.', NOW())
ON CONFLICT DO NOTHING;
