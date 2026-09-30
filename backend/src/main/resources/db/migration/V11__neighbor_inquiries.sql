ALTER TABLE shelter.community_posts ADD CONSTRAINT community_post_author UNIQUE(id,author_id);
CREATE TABLE shelter.inquiry_rooms (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid NOT NULL,
    author_id uuid NOT NULL REFERENCES shelter.app_users(id),
    requester_id uuid NOT NULL REFERENCES shelter.app_users(id),
    last_sequence bigint NOT NULL DEFAULT 0 CHECK(last_sequence>=0),
    author_read_sequence bigint NOT NULL DEFAULT 0,
    requester_read_sequence bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(post_id,requester_id),
    FOREIGN KEY(post_id,author_id) REFERENCES shelter.community_posts(id,author_id),
    CHECK(author_id<>requester_id),
    CHECK(author_read_sequence BETWEEN 0 AND last_sequence AND requester_read_sequence BETWEEN 0 AND last_sequence)
);
CREATE INDEX inquiry_rooms_author_page ON shelter.inquiry_rooms(author_id,updated_at DESC,id DESC);
CREATE INDEX inquiry_rooms_requester_page ON shelter.inquiry_rooms(requester_id,updated_at DESC,id DESC);
ALTER TABLE shelter.community_media ADD COLUMN room_id uuid REFERENCES shelter.inquiry_rooms(id);
ALTER TABLE shelter.community_media ADD CONSTRAINT community_media_single_context CHECK(post_id IS NULL OR room_id IS NULL);
CREATE TABLE shelter.inquiry_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id uuid NOT NULL REFERENCES shelter.inquiry_rooms(id),
    sequence bigint NOT NULL CHECK(sequence>0),
    sender_id uuid NOT NULL REFERENCES shelter.app_users(id),
    client_message_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    kind varchar(10) NOT NULL CHECK(kind IN ('TEXT','IMAGE','LOCATION')),
    text varchar(1000) NOT NULL,
    media_id uuid REFERENCES shelter.community_media(id),
    location jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(room_id,sequence),
    UNIQUE(room_id,sender_id,client_message_id),
    CHECK((kind='TEXT' AND length(text)>0 AND media_id IS NULL AND location IS NULL)
       OR (kind='IMAGE' AND media_id IS NOT NULL AND location IS NULL)
       OR (kind='LOCATION' AND media_id IS NULL AND location IS NOT NULL AND jsonb_typeof(location)='object'))
);
CREATE INDEX inquiry_messages_unread ON shelter.inquiry_messages(room_id,sender_id,sequence);
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['inquiry_rooms','inquiry_messages'] LOOP
        EXECUTE format('ALTER TABLE shelter.%I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('REVOKE ALL ON shelter.%I FROM PUBLIC, anon, authenticated',t);
        EXECUTE format('GRANT SELECT, INSERT ON shelter.%I TO shelter_runtime',t);
        EXECUTE format('CREATE POLICY runtime_server_access ON shelter.%I TO shelter_runtime USING (true) WITH CHECK (true)',t);
    END LOOP;
END $$;
GRANT UPDATE ON shelter.inquiry_rooms TO shelter_runtime;
-- Messages are immutable; runtime cannot edit or delete previously delivered content.
