-- V002 declared these rating columns as SMALLINT, but their JPA entities (Review and
-- ReviewEditHistory) use Java int, which Hibernate schema validation expects to map to
-- INTEGER. Widening here instead of editing V002 in place, since V002 already has a
-- recorded Flyway checksum.
ALTER TABLE review_schema.review ALTER COLUMN salon_rating TYPE INTEGER;
ALTER TABLE review_schema.review ALTER COLUMN stylist_rating TYPE INTEGER;
ALTER TABLE review_schema.review_edit_history ALTER COLUMN salon_rating TYPE INTEGER;
ALTER TABLE review_schema.review_edit_history ALTER COLUMN stylist_rating TYPE INTEGER;
