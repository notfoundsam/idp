-- The card of a user reads the newest records about them.
CREATE INDEX audit_entries_target ON audit_entries (target_type, target_id, occurred_at DESC, id DESC);
