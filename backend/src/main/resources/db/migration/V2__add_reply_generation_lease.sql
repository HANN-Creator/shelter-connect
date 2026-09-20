-- Keep public message states stable; an expiring token fences internal generation attempts.
ALTER TABLE shelter.chat_messages
    ADD COLUMN generation_token uuid,
    ADD COLUMN generation_expires_at timestamptz,
    ADD COLUMN generation_attempts integer NOT NULL DEFAULT 0 CHECK (generation_attempts BETWEEN 0 AND 3),
    ADD COLUMN generation_model varchar(100),
    ADD COLUMN generation_response_id varchar(128),
    ADD CONSTRAINT message_generation_lease CHECK (
        (generation_token IS NULL AND generation_expires_at IS NULL) OR
        (generation_token IS NOT NULL AND generation_expires_at IS NOT NULL
            AND role = 'USER' AND processing_status = 'PENDING' AND generation_attempts > 0)
    );
CREATE INDEX messages_active_generation_idx ON shelter.chat_messages(session_id, generation_expires_at)
    WHERE generation_token IS NOT NULL;
-- Existing schema permissions and RLS continue to apply; no client grants are added.
