-- V002 declared booking_disruption.rejection_count as SMALLINT, but the JPA entity
-- (BookingDisruption.rejectionCount) is a Java int, which Hibernate schema validation
-- expects to map to INTEGER. Widening here instead of editing V002 in place, since V002
-- already has a recorded Flyway checksum.
ALTER TABLE booking_schema.booking_disruption ALTER COLUMN rejection_count TYPE INTEGER;
