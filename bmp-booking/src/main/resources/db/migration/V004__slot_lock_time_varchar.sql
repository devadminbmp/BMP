-- slot_lock.start_time/end_time were declared TIME, but the JPA entity (SlotLock) stores
-- them as plain Strings, matching the same "no extra type mapping wired in this pass"
-- pattern used across bmp-salon's time columns.
ALTER TABLE booking_schema.slot_lock ALTER COLUMN start_time TYPE VARCHAR(255) USING start_time::text;
ALTER TABLE booking_schema.slot_lock ALTER COLUMN end_time TYPE VARCHAR(255) USING end_time::text;
