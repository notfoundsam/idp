-- A KEK replaced by a move still opens the backups taken before it, so `worker` destroys it only after they expire.
-- No destruction time means the tenant key is not yet wrapped by the new KEK.
ALTER TABLE tenants
    ADD COLUMN previous_kek_uri           text,
    ADD COLUMN previous_kek_destroy_after timestamptz,
    ADD CHECK (previous_kek_destroy_after IS NULL OR previous_kek_uri IS NOT NULL);
