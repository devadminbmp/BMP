-- Same "no extra type mapping wired in this pass" pattern as V006: these entities
-- (StylistAvailability, WalkInBlock) store times as plain Strings, not java.time.LocalTime.
ALTER TABLE salon_schema.stylist_availability ALTER COLUMN start_time TYPE VARCHAR(255) USING start_time::text;
ALTER TABLE salon_schema.stylist_availability ALTER COLUMN end_time TYPE VARCHAR(255) USING end_time::text;
ALTER TABLE salon_schema.walk_in_block ALTER COLUMN start_time TYPE VARCHAR(255) USING start_time::text;
