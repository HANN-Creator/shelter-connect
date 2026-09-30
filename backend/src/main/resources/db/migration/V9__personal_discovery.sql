CREATE TABLE shelter.user_preferences (
    user_id uuid PRIMARY KEY REFERENCES shelter.app_users(id),
    current_shelter_id uuid REFERENCES shelter.shelters(id),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE shelter.user_consents (
    user_id uuid NOT NULL REFERENCES shelter.app_users(id),
    terms_version varchar(40) NOT NULL,
    privacy_version varchar(40) NOT NULL,
    accepted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(user_id, terms_version, privacy_version),
    CHECK(terms_version ~ '^[A-Za-z0-9._-]{1,40}$' AND privacy_version ~ '^[A-Za-z0-9._-]{1,40}$')
);
CREATE TABLE shelter.saved_dogs (
    user_id uuid NOT NULL REFERENCES shelter.app_users(id),
    dog_id uuid NOT NULL REFERENCES shelter.dogs(id),
    active boolean NOT NULL DEFAULT true,
    saved_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(user_id,dog_id)
);
CREATE INDEX saved_dogs_page ON shelter.saved_dogs(user_id,saved_at DESC,dog_id DESC) WHERE active;
CREATE INDEX chat_sessions_activity ON shelter.chat_sessions(user_id,updated_at DESC,id DESC);
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['user_preferences','user_consents','saved_dogs'] LOOP
        EXECUTE format('ALTER TABLE shelter.%I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('REVOKE ALL ON shelter.%I FROM PUBLIC, anon, authenticated',t);
        EXECUTE format('GRANT SELECT, INSERT ON shelter.%I TO shelter_runtime',t);
        EXECUTE format('CREATE POLICY runtime_server_access ON shelter.%I TO shelter_runtime USING (true) WITH CHECK (true)',t);
    END LOOP;
END $$;
GRANT UPDATE ON shelter.user_preferences, shelter.saved_dogs TO shelter_runtime;
-- Consent history is append-only. No authority columns are granted.
