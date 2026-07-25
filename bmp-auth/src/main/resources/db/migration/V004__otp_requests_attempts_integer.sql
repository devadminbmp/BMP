-- V002 declared otp_requests.attempts as SMALLINT, but the JPA entity (OtpRequests.attempts)
-- is a Java int, which Hibernate schema validation expects to map to INTEGER. Widening here
-- instead of editing V002 in place, since V002 already has a recorded Flyway checksum.
ALTER TABLE user_schema.otp_requests ALTER COLUMN attempts TYPE INTEGER;
