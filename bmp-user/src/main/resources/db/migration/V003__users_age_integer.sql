-- V002 declared users.age as SMALLINT, but the JPA entity (Users.age) is a Java int,
-- which Hibernate schema validation expects to map to INTEGER. Widening here instead of
-- editing V002 in place, since V002 already has a recorded Flyway checksum.
ALTER TABLE user_schema.users ALTER COLUMN age TYPE INTEGER;
