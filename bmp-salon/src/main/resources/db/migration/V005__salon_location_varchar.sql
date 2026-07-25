-- V002 declared salon.location as GEOGRAPHY(POINT) (PostGIS), but SalonService's own
-- javadoc confirms hibernate-spatial was never wired in this pass: location is stored as
-- a plain "lat,lng" String and proximity search is in-memory Haversine, not ST_DWithin.
-- Aligning the column with what the code actually does; swap to a real Point/JTS mapping
-- together when the proximity-search ticket is built.
ALTER TABLE salon_schema.salon ALTER COLUMN location TYPE VARCHAR(255) USING ST_AsText(location);
