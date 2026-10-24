-- Room common photos: a finding is an issue or a room's overview photos (kind = 'ROOM_PHOTOS').
-- Additive only. NULL means an issue: rows from apps before 0.9 never send a kind, and their
-- inserts must still be accepted, so there is no NOT NULL / DEFAULT to collide with.
ALTER TABLE findings ADD COLUMN kind text;
