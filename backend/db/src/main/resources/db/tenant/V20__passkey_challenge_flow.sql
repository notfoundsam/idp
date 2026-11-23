-- A login challenge belongs to the flow that asked for it, so a response to it passes in that flow only. Flows belong to another
-- context, so there is no foreign key. A login challenge lives five minutes, and the ones from before have no flow to keep.
DELETE FROM passkey_challenges WHERE user_id IS NULL;

ALTER TABLE passkey_challenges
    ADD COLUMN flow_id uuid,
    ADD CONSTRAINT passkey_challenges_one_owner CHECK ((user_id IS NULL) <> (flow_id IS NULL));
