-- V002 declared payout_batch.retry_count as SMALLINT, but the JPA entity
-- (PayoutBatch.retryCount) is a Java int, which Hibernate schema validation expects to
-- map to INTEGER. Widening here instead of editing V002 in place, since V002 already has
-- a recorded Flyway checksum.
ALTER TABLE payment_schema.payout_batch ALTER COLUMN retry_count TYPE INTEGER;
