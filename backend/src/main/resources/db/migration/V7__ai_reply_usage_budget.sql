-- Persistent reservations survive restarts and serialize limits across server instances.
CREATE TABLE shelter.ai_reply_usage (
    token uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES shelter.app_users(id) ON DELETE CASCADE,
    request_message_id uuid NOT NULL REFERENCES shelter.chat_messages(id) ON DELETE CASCADE,
    reserved_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    released_at timestamptz,
    CHECK (expires_at > reserved_at)
);
CREATE INDEX ai_reply_usage_time_idx ON shelter.ai_reply_usage(reserved_at);
CREATE INDEX ai_reply_usage_user_time_idx ON shelter.ai_reply_usage(user_id, reserved_at);
CREATE INDEX ai_reply_usage_active_idx ON shelter.ai_reply_usage(expires_at) WHERE released_at IS NULL;
ALTER TABLE shelter.ai_reply_usage ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON shelter.ai_reply_usage FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='anon') THEN
        REVOKE ALL ON shelter.ai_reply_usage FROM anon;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='authenticated') THEN
        REVOKE ALL ON shelter.ai_reply_usage FROM authenticated;
    END IF;
END $$;
