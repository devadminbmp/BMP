-- V004__users_integrity_and_deactivation.sql  (Session 13 — bmp-user completion pass)

-- 1. users.phone: V002's comment says "UK, E.164 identity key" but no UNIQUE constraint
--    was ever actually declared — only UserService's app-level existsByPhone() check,
--    which is racy under two concurrent signups for the same number. Enforce at the DB.
ALTER TABLE user_schema.users ADD CONSTRAINT uk_users_phone UNIQUE (phone);

-- 2. user_roles: nothing prevented granting the same user the same role (for the same
--    salon) twice. COALESCE to the zero UUID because Postgres treats NULLs as distinct
--    in unique indexes — two (user, customer, NULL) rows would otherwise both be allowed.
CREATE UNIQUE INDEX uq_user_roles_dedup ON user_schema.user_roles
    (user_id, role, COALESCE(salon_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- 3. Soft deactivation (Instagram-style: deactivating hides the account; logging in
--    again reactivates it — see UserService.deactivate/reactivate and bmp-auth's login
--    flow). NULL = active. Additive column, not a status enum rewrite, so nothing about
--    the existing is_verified/default_role columns changes.
ALTER TABLE user_schema.users ADD COLUMN deactivated_at TIMESTAMPTZ;
