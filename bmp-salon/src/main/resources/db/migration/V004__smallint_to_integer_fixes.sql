-- V002/V003 declared these as SMALLINT, but their JPA entities use Java int, which
-- Hibernate schema validation expects to map to INTEGER. Widening here instead of editing
-- V002/V003 in place, since they already have recorded Flyway checksums.
ALTER TABLE salon_schema.salon_hours ALTER COLUMN day_of_week TYPE INTEGER;
ALTER TABLE salon_schema.salon_combo_item ALTER COLUMN sequence TYPE INTEGER;
ALTER TABLE salon_schema.stylist_availability ALTER COLUMN day_of_week TYPE INTEGER;
