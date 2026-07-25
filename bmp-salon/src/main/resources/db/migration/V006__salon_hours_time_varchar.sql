-- salon_hours.open_time/close_time were declared TIME, but the JPA entity (SalonHours)
-- stores them as plain Strings ("HH:mm"), matching the same "no extra type mapping wired
-- in this pass" pattern as salon.location (see V005).
ALTER TABLE salon_schema.salon_hours ALTER COLUMN open_time TYPE VARCHAR(255) USING open_time::text;
ALTER TABLE salon_schema.salon_hours ALTER COLUMN close_time TYPE VARCHAR(255) USING close_time::text;
