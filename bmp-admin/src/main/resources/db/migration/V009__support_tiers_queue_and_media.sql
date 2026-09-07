-- ═══════════════════════════════════════════════════════════════════════════════════════════════
-- V009 — the support organisation: tiers, a real queue, escalation, and media. Session 57.
-- ═══════════════════════════════════════════════════════════════════════════════════════════════
--
-- ── WHAT DARSHAN ASKED FOR, AND WHAT THE INDUSTRY CALLS IT ─────────────────────────────────────
-- The ask: customers raise a help conversation per booking with media; support handles it; if they
-- cannot resolve it, it escalates upward WITH the whole history; every support member gets their
-- own tickets; new joiners enter the rotation automatically; some — not all — ops admins can
-- create support accounts.
--
-- That is, almost exactly, the standard support desk every large Indian consumer platform runs
-- (Swiggy, Zomato, Rapido, Blinkit all run Zendesk/Freshdesk-shaped operations). The shape is:
--
--   L1  front-line agent      — owns the first reply, resolves the common cases
--   L2  team lead / senior    — takes what L1 cannot, coaches, owns the queue's health
--   L3  ops                   — policy, money, anything touching a salon's standing
--   L4  admin / owner         — everything, including who gets to be staff
--
-- BMP had L1, L3 and L4 and NO L2. That missing rung is why the ask reads as "support escalates to
-- ops admin": with nothing between them, ops becomes the first and only escalation, and a role
-- meant for policy decisions spends its day on individual complaints. Every comparable org has
-- this tier, so `support_lead` is added.
--
-- Darshan's "ops admin (analysts)" maps to the EXISTING `read_only` role — read the platform,
-- own nothing. It stays out of the escalation ladder deliberately: an analyst who can be assigned
-- a customer's complaint is not an analyst. They can read tickets; they cannot hold one.
--
-- `finance_admin` also stays off the ladder. Refunds are a different axis, not a higher rung —
-- the same reason those companies route money to a finance queue rather than up the support chain.
--
-- ── THE LADDER, AS A NUMBER ────────────────────────────────────────────────────────────────────
-- Tier is stored as an INT rather than inferred from the role string. Escalation is then "find
-- somebody at a higher tier", which is one comparison and survives a role being renamed. Inferring
-- it from role names would put the ladder in a switch statement in whichever service escalates,
-- and there would eventually be two of them that disagree.

-- ── 1. STAFF: tier, and who may hire ───────────────────────────────────────────────────────────

ALTER TABLE admin_schema.bmp_staff
    ADD COLUMN IF NOT EXISTS tier SMALLINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN admin_schema.bmp_staff.tier IS
    '1=support_agent, 2=support_lead, 3=ops_admin, 4=super_admin. 0 = not in the escalation '
    'ladder at all (read_only analysts, finance_admin) — they can read but never hold a ticket. '
    'Stored rather than derived from the role string so escalation is one integer comparison and '
    'does not become a switch statement duplicated in every service that escalates. See V009.';

-- Backfill from the role each existing member already has.
UPDATE admin_schema.bmp_staff SET tier = CASE role
    WHEN 'super_admin'   THEN 4
    WHEN 'ops_admin'     THEN 3
    WHEN 'support_lead'  THEN 2
    WHEN 'support_agent' THEN 1
    ELSE 0                      -- finance_admin, read_only: not on the ladder
END;

ALTER TABLE admin_schema.bmp_staff
    ADD CONSTRAINT chk_staff_tier CHECK (tier BETWEEN 0 AND 4);

/*
 * "Ops admin can create support accounts — but not ALL ops admins."
 *
 * A CAPABILITY FLAG, not a new role. The alternative — inventing `ops_admin_senior` — doubles the
 * role list every time one ops admin needs one extra power, and the permission matrix becomes a
 * combinatorial mess. This is how Zendesk and Freshdesk do it too: a role, plus explicitly granted
 * privileges on the individual.
 *
 * Only a super_admin may set it, and it is FALSE by default: a new ops admin cannot hire until the
 * owner says so. Granting the ability to create accounts is the single most consequential thing
 * one staff member can do to the platform, so it defaults closed.
 */
ALTER TABLE admin_schema.bmp_staff
    ADD COLUMN IF NOT EXISTS can_manage_staff BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN admin_schema.bmp_staff.can_manage_staff IS
    'Granted individually by a super_admin. Lets THIS ops admin create and suspend support/lead '
    'accounts — never other ops admins or super admins, which stays super_admin-only. Default '
    'false: hiring is opt-in, not a side effect of the role. See V009.';

/*
 * Round-robin state. Two columns, and the pair is what makes assignment fair.
 *
 * `open_ticket_count` is denormalised deliberately: picking the next assignee is on the hot path
 * of every incoming ticket, and counting open tickets per agent across the table to answer it
 * would get slower exactly as the desk gets busier.
 *
 * `accepting_tickets` is the agent's own switch — on leave, in a meeting, end of shift. New staff
 * default to TRUE, which is what "new joiners are automatically included in the queue" means.
 */
ALTER TABLE admin_schema.bmp_staff
    ADD COLUMN IF NOT EXISTS accepting_tickets BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE admin_schema.bmp_staff
    ADD COLUMN IF NOT EXISTS open_ticket_count INT NOT NULL DEFAULT 0;

ALTER TABLE admin_schema.bmp_staff
    ADD CONSTRAINT chk_staff_open_count CHECK (open_ticket_count >= 0);

-- The assignment query: everyone on the ladder at a given tier who is taking work, least-loaded
-- first. This index IS the round robin — without it, every new ticket scans the staff table.
CREATE INDEX IF NOT EXISTS idx_staff_assignable
    ON admin_schema.bmp_staff (tier, open_ticket_count)
    WHERE status = 'active' AND accepting_tickets = true AND tier > 0;

-- ── 2. TICKETS: which tier owns it, and the SLA clock ──────────────────────────────────────────

ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS tier SMALLINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN admin_schema.support_ticket.tier IS
    'Which tier currently owns this ticket. Escalation raises it; it never goes down on its own. '
    'Assignment picks from staff at exactly this tier, so a ticket escalated to 3 cannot land back '
    'on an L1 agent. See V009.';

ALTER TABLE admin_schema.support_ticket
    ADD CONSTRAINT chk_ticket_tier CHECK (tier BETWEEN 1 AND 4);

ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS escalated_at TIMESTAMPTZ;

ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS escalation_count INT NOT NULL DEFAULT 0;

/*
 * Who the customer is waiting on, as a first-class fact.
 *
 * `status` already has waiting_on_user, but a queue needs the inverse too: "we owe this person a
 * reply, and for how long". first_response_at and last_staff_reply_at answer the two questions
 * every support desk is measured on, and computing them by scanning the message thread per row is
 * the thing that makes a queue page slow.
 */
ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS first_response_at TIMESTAMPTZ;

ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS last_customer_message_at TIMESTAMPTZ;

ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS last_staff_message_at TIMESTAMPTZ;

-- THE QUEUE QUERY. SupportDeskController currently loads every ticket and filters in Java, which
-- works at ten tickets and not at ten thousand. Ordered the way an agent should work: unassigned
-- first, then oldest unanswered.
CREATE INDEX IF NOT EXISTS idx_ticket_queue
    ON admin_schema.support_ticket (tier, status, assigned_staff_id, created_at);

CREATE INDEX IF NOT EXISTS idx_ticket_assignee
    ON admin_schema.support_ticket (assigned_staff_id, status)
    WHERE assigned_staff_id IS NOT NULL;

-- ── 3. THE ESCALATION TRAIL ────────────────────────────────────────────────────────────────────
/*
 * Every handover, kept forever.
 *
 * Darshan: "if support can resolve it, it should be passed to ops admins — he must get the same
 * and previous chat history, he can continue."
 *
 * The chat history comes free: messages belong to the TICKET, and escalation does not create a new
 * ticket. That is the whole design decision — a new ticket per escalation would split the
 * conversation, which is what makes a customer repeat themselves to the third person they speak
 * to, and it is the single most complained-about thing in phone support.
 *
 * What this table adds is the trail: who handed it up, to whom, why, and when. Without it, an
 * escalated ticket shows the current owner and no record that anyone else touched it.
 */
CREATE TABLE IF NOT EXISTS admin_schema.ticket_escalation (
    id UUID PRIMARY KEY NOT NULL,
    ticket_id UUID NOT NULL REFERENCES admin_schema.support_ticket(id),

    from_tier SMALLINT NOT NULL,
    to_tier SMALLINT NOT NULL,

    -- Who escalated, and who received it. The receiver can be null when it was escalated into an
    -- unclaimed pool — a real state at 2am, and better than assigning to somebody asleep.
    from_staff_id UUID NOT NULL,
    to_staff_id UUID,

    -- REQUIRED. "Escalated" with no reason is the thing that makes the next person start from
    -- scratch, which defeats the purpose of keeping the history.
    reason TEXT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE admin_schema.ticket_escalation
    ADD CONSTRAINT chk_escalation_upward CHECK (to_tier > from_tier);

ALTER TABLE admin_schema.ticket_escalation
    ADD CONSTRAINT chk_escalation_reason CHECK (length(trim(reason)) >= 10);

CREATE INDEX IF NOT EXISTS idx_escalation_ticket
    ON admin_schema.ticket_escalation (ticket_id, created_at);

-- ── 4. MEDIA IN THE CHAT ───────────────────────────────────────────────────────────────────────
/*
 * `support_message.attachment_url VARCHAR(500)` — one attachment, as a URL string.
 *
 * Three things wrong with it, and they are why this is a table:
 *   · ONE. A customer photographing a bad colour job sends three pictures, not one.
 *   · A URL, not a storage key. Everything else that holds an uploaded image in this platform
 *     (salon photos, service photos — V015 in salon_schema) stores a `storage_key` and signs a URL
 *     on read, so the object can be private. A raw URL column invites a public bucket.
 *   · No type, no size, no original name. A UI cannot render an image inline and a PDF as a chip
 *     without knowing which it is, and an unbounded size is an upload endpoint's whole risk.
 *
 * The old column is LEFT IN PLACE and unused rather than dropped — it may hold data on tickets
 * raised before this, and destroying that to tidy a schema is not a trade worth making.
 */
CREATE TABLE IF NOT EXISTS admin_schema.support_attachment (
    id UUID PRIMARY KEY NOT NULL,
    message_id UUID NOT NULL REFERENCES admin_schema.support_message(id),
    ticket_id UUID NOT NULL REFERENCES admin_schema.support_ticket(id),

    -- The object in MinIO/S3. NOT a URL — reads sign a short-lived one, same as salon photos.
    storage_key VARCHAR(500) NOT NULL,

    -- What the customer called it. Shown for documents; ignored for images.
    file_name VARCHAR(255),

    -- Validated by MAGIC BYTES on upload, not by trusting the client's Content-Type. Same rule as
    -- ImageIngest (Session 42): a file claiming to be a PNG is not a PNG.
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,

    uploaded_by_type VARCHAR(20) NOT NULL,   -- customer | bmp_staff
    uploaded_by_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE admin_schema.support_attachment
    ADD CONSTRAINT chk_attachment_size CHECK (size_bytes > 0 AND size_bytes <= 26214400); -- 25 MB

ALTER TABLE admin_schema.support_attachment
    ADD CONSTRAINT chk_attachment_uploader
        CHECK (uploaded_by_type IN ('customer', 'bmp_staff', 'salon_owner', 'manager'));

CREATE INDEX IF NOT EXISTS idx_attachment_message
    ON admin_schema.support_attachment (message_id);

CREATE INDEX IF NOT EXISTS idx_attachment_ticket
    ON admin_schema.support_attachment (ticket_id, created_at);

-- ── 5. UNREAD, SO A THREAD CAN SHOW A BADGE ────────────────────────────────────────────────────
-- Per side, not per person: a ticket has one customer and whichever staff member currently owns
-- it, so two timestamps answer "is there something new for me?" without a read-receipt table.
ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS customer_last_read_at TIMESTAMPTZ;

ALTER TABLE admin_schema.support_ticket
    ADD COLUMN IF NOT EXISTS staff_last_read_at TIMESTAMPTZ;

-- Message ordering within a thread — the single most-run query once chat is live.
CREATE INDEX IF NOT EXISTS idx_message_thread
    ON admin_schema.support_message (ticket_id, created_at);
