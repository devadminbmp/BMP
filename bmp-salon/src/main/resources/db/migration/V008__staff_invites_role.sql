-- Session 17: staff_invites becomes role-aware, so a salon can invite STYLISTS as well as
-- managers.
--
-- WHY THIS MIGRATION EXISTS
-- The original table (V002) had no role column, which is why StaffDtos' Javadoc explicitly
-- scoped invites to MANAGER only: "staff_invites' locked columns have no room for a role
-- distinction". Inviting a stylist through the same one-time-token mechanism needs that
-- distinction, and a second near-identical table would be worse than one column.
--
-- WHAT A STYLIST INVITE DOES DIFFERENTLY
-- A manager invite creates a salon_staff seat (dashboard access, scoped to one salon).
-- A stylist invite creates a stylist_salon LINK — the portable-identity join. A stylist is
-- never scoped to a salon in their JWT; they can work at more than one over time, and their
-- profile and reviews travel with them. Same envelope, different contents.
--
-- BACKWARD COMPATIBILITY
-- role DEFAULT 'manager' means every existing pending invite keeps working exactly as before,
-- with no data backfill and no coordinated deploy. NOT NULL is safe for the same reason.

ALTER TABLE salon_schema.staff_invites
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'manager';

-- The invitee's name, captured when the invite is created.
--
-- Purely for the ISSUER's benefit: a pending-invites list showing "+919876543210" tells an
-- owner nothing three days later, whereas "Ravi Kumar · +919876543210" does. The invitee still
-- supplies their own name at signup — this is never trusted as their profile name, because the
-- person who typed it isn't the person it describes.
ALTER TABLE salon_schema.staff_invites
    ADD COLUMN invitee_name VARCHAR(120);

-- Filtering "pending stylist invites for this salon" is the single most common read on this
-- table now that it holds two kinds of row.
CREATE INDEX idx_staff_invites_salon_role_status
    ON salon_schema.staff_invites(salon_id, role, status);
