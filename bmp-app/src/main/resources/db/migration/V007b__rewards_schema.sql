-- V007b__rewards_schema.sql
-- Schema: rewards_schema  |  Module: bmp-rewards
-- Generated from the locked table definitions in CONTEXT.md.
-- NOTE: bmp-rewards/package-info.java documented a simpler 5-table version;
-- implementing CONTEXT.md's fuller 10-table design instead. loyalty_account/
-- loyalty_transaction are schema-ready but NOT ACTIVE in Phase 1 (feature flag off).

CREATE SCHEMA IF NOT EXISTS rewards_schema;

CREATE TABLE rewards_schema.coupon (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    code VARCHAR(40) NOT NULL,  -- UK
    coupon_type VARCHAR(20) NOT NULL,  -- welcome/referral/loyalty/off_peak/festival/birthday/win_back/salon_specific
    salon_id UUID,  -- NULL = platform-wide
    commission_base VARCHAR(15) NOT NULL,  -- pre_discount/post_discount — salon_specific ALWAYS pre_discount
    discount_type VARCHAR(10) NOT NULL,  -- flat/percent
    value BIGINT NOT NULL,  -- paise if flat, basis points if percent
    min_spend_paise BIGINT NOT NULL,
    per_user_limit INT NOT NULL,
    total_usage_cap INT,
    active_from TIMESTAMPTZ NOT NULL,
    active_to TIMESTAMPTZ NOT NULL,
    allows_wallet_stacking BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rewards_schema.coupon_usage (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    coupon_id UUID NOT NULL,  -- FK -> coupon.id
    user_id UUID NOT NULL,
    booking_id UUID NOT NULL,
    discount_applied_paise BIGINT NOT NULL,
    was_refunded BOOLEAN NOT NULL,  -- ONLY true if payment failed before confirmation
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rewards_schema.wallet (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    user_id UUID NOT NULL,  -- UK
    balance_paise BIGINT NOT NULL,  -- never negative — CHECK constraint
    is_frozen BOOLEAN NOT NULL,  -- fraud investigation
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rewards_schema.wallet_transaction (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    wallet_id UUID NOT NULL,  -- FK -> wallet.id
    transaction_type VARCHAR(30) NOT NULL,  -- one of 9 transaction types
    amount_paise BIGINT NOT NULL,  -- signed
    balance_after_paise BIGINT NOT NULL,  -- snapshot
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rewards_schema.referral (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    referrer_user_id UUID NOT NULL,
    referee_user_id UUID NOT NULL,
    referrer_reward_paise BIGINT NOT NULL,  -- FROZEN at referral creation, Rs150
    referee_reward_paise BIGINT NOT NULL,  -- FROZEN at referral creation, Rs100
    referred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,  -- referred_at + 90 days
    fraud_reason VARCHAR(20),  -- same_device/same_ip/self_referral/already_referred
    completed_at TIMESTAMPTZ  -- set on referee's FIRST COMPLETED VISIT
);

CREATE TABLE rewards_schema.referral_code (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    user_id UUID NOT NULL,  -- UK
    code VARCHAR(30) NOT NULL,  -- UK, e.g. DARSHAN-X7K
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rewards_schema.checkout_discount (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    booking_id UUID NOT NULL,  -- UK — FROZEN at checkout confirmation
    coupon_id UUID,
    discount_paise BIGINT NOT NULL,
    commission_base_paise BIGINT NOT NULL,  -- locked pre or post discount, never recalculated
    final_charge_paise BIGINT NOT NULL,  -- what Razorpay charges
    coupon_restore_policy VARCHAR(30) NOT NULL  -- restore_on_pre_confirm_only
);

CREATE TABLE rewards_schema.win_back_job_log (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    user_id UUID NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,  -- 60 days since last booking anywhere on BMP
    reactivated BOOLEAN NOT NULL  -- booked within 30 days = conversion
);

CREATE TABLE rewards_schema.loyalty_account (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    user_id UUID NOT NULL,  -- UK — schema-ready, feature flag OFF in Phase 1
    points_balance INT NOT NULL,
    tier VARCHAR(10) NOT NULL,  -- bronze/silver/gold/platinum
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rewards_schema.loyalty_transaction (
    id UUID PRIMARY KEY NOT NULL,  -- PK, UUIDv7
    loyalty_account_id UUID NOT NULL,  -- FK -> loyalty_account.id
    points_delta INT NOT NULL,
    reason VARCHAR(60) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- Foreign keys within rewards_schema (cross-schema FKs are logical only, per architecture rule)
ALTER TABLE rewards_schema.coupon_usage ADD CONSTRAINT fk_coupon_usage_coupon_id FOREIGN KEY (coupon_id) REFERENCES rewards_schema.coupon(id);
ALTER TABLE rewards_schema.wallet_transaction ADD CONSTRAINT fk_wallet_transaction_wallet_id FOREIGN KEY (wallet_id) REFERENCES rewards_schema.wallet(id);
ALTER TABLE rewards_schema.loyalty_transaction ADD CONSTRAINT fk_loyalty_transaction_loyalty_account_id FOREIGN KEY (loyalty_account_id) REFERENCES rewards_schema.loyalty_account(id);

-- Append-only tables: DELETE/UPDATE revoked at DB level (Schema Rule).
-- <app_role> must be replaced with the actual DB role bmp-app connects as
-- (see bmp-app/src/main/resources/application.yml spring.datasource.username).
REVOKE UPDATE, DELETE ON rewards_schema.wallet_transaction FROM PUBLIC;

-- Indexes on FK / lookup columns
CREATE INDEX idx_coupon_salon_id ON rewards_schema.coupon(salon_id);
CREATE INDEX idx_coupon_usage_coupon_id ON rewards_schema.coupon_usage(coupon_id);
CREATE INDEX idx_coupon_usage_user_id ON rewards_schema.coupon_usage(user_id);
CREATE INDEX idx_coupon_usage_booking_id ON rewards_schema.coupon_usage(booking_id);
CREATE INDEX idx_wallet_user_id ON rewards_schema.wallet(user_id);
CREATE INDEX idx_wallet_transaction_wallet_id ON rewards_schema.wallet_transaction(wallet_id);
CREATE INDEX idx_referral_referrer_user_id ON rewards_schema.referral(referrer_user_id);
CREATE INDEX idx_referral_referee_user_id ON rewards_schema.referral(referee_user_id);
CREATE INDEX idx_referral_code_user_id ON rewards_schema.referral_code(user_id);
CREATE INDEX idx_checkout_discount_booking_id ON rewards_schema.checkout_discount(booking_id);
CREATE INDEX idx_checkout_discount_coupon_id ON rewards_schema.checkout_discount(coupon_id);
CREATE INDEX idx_win_back_job_log_user_id ON rewards_schema.win_back_job_log(user_id);
CREATE INDEX idx_loyalty_account_user_id ON rewards_schema.loyalty_account(user_id);
CREATE INDEX idx_loyalty_transaction_loyalty_account_id ON rewards_schema.loyalty_transaction(loyalty_account_id);
