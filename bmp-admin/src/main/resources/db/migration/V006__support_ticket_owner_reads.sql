-- V006__support_ticket_owner_reads.sql  (Session 45)
--
-- ═════════════════════════════════════════════════════════════════════════════════════════════
-- Support tickets become readable by the people who raised them.
-- ═════════════════════════════════════════════════════════════════════════════════════════════
--
-- Until now `support_ticket` was written and read exclusively by BMP staff, and in practice not
-- even that: `SupportTicketController` is `hasRole('SERVICE')` and NOTHING called it, in any of
-- the three repos. The console could list, triage, reply to and close tickets — but no path
-- existed by which a ticket could ever come into existence. A complete support desk with the
-- phone line unplugged.
--
-- Session 45 opens the door (bmp-user's /api/v1/support, proxying to bmp-admin over the internal
-- service key). That changes the read patterns on this table, and this migration is only about
-- those — the COLUMNS were already right.
--
-- ---------------------------------------------------------------------------------------------
-- WHY THIS MIGRATION IS SO SMALL
-- ---------------------------------------------------------------------------------------------
-- I expected to add salon_id, requester contact and a waiting-on-us signal, and went looking
-- before writing. V003 had already added `salon_id`, `requester_email`, `requester_phone`, the
-- three SLA timestamps and `support_message.internal_note` — and `status` already carries
-- `waiting_on_user`. The schema was designed for exactly this and only the door was missing.
--
-- So: two indexes, no columns. Adding a `salon_id` that already exists would have been a
-- migration that silently did nothing, and a later reader would have had to work out which of
-- the two definitions won.
-- ---------------------------------------------------------------------------------------------

-- "My tickets", newest first. About to become the most frequent read on this table: every owner
-- and customer who opens the Help tab runs it, which is far more often than staff open the queue.
-- Nothing indexed raised_by_id at all before — V003 indexed the STAFF paths (status/priority/SLA)
-- because staff were the only readers there were.
--
-- Composite rather than two indexes: the query is always "this requester, ordered by recency",
-- so the sort comes free from the index instead of costing a sort of every ticket they ever
-- raised.
CREATE INDEX IF NOT EXISTS idx_support_ticket_requester
    ON admin_schema.support_ticket (raised_by_id, created_at DESC);

-- A salon's tickets, for two different readers:
--   · the owner, who should see what their MANAGER raised — support is a salon-level concern,
--     not a personal one, and an owner chasing "did anyone report the payout problem?" should
--     not have to ask each manager individually;
--   · staff filtering the queue by salon when a salon calls about several issues at once.
--
-- Partial, because most tickets are from customers and carry no salon_id. Indexing those NULLs
-- would roughly double the index for rows it can never usefully return.
CREATE INDEX IF NOT EXISTS idx_support_ticket_salon
    ON admin_schema.support_ticket (salon_id, created_at DESC)
    WHERE salon_id IS NOT NULL;

COMMENT ON COLUMN admin_schema.support_ticket.raised_by_id IS
    'The user who opened the ticket. Session 45: now also the OWNERSHIP key — bmp-admin checks '
    'a reader against this (or salon_id) before returning a ticket. The calling service passes '
    'an identity; it does not get to say which tickets that identity may read.';

COMMENT ON COLUMN admin_schema.support_ticket.raised_by_type IS
    'customer / salon_owner / manager / stylist / bmp_staff. Session 45: DERIVED FROM THE JWT at '
    'the bmp-user boundary and never taken from the request body — otherwise a customer could '
    'post raised_by_type=bmp_staff and impersonate support.';
