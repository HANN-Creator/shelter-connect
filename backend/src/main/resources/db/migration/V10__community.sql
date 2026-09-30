ALTER TABLE shelter.user_preferences ADD COLUMN community_region varchar(100);
CREATE TABLE shelter.community_posts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id uuid NOT NULL REFERENCES shelter.app_users(id),
    client_request_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    category varchar(20) NOT NULL CHECK(category IN ('LOST','FOUND','NEIGHBOR_NEWS')),
    publication varchar(12) NOT NULL CHECK(publication IN ('DRAFT','PUBLISHED')),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REUNITED','CLOSED','TRANSFERRED')),
    content jsonb NOT NULL CHECK(jsonb_typeof(content)='object'),
    version bigint NOT NULL DEFAULT 1 CHECK(version>0),
    hidden_at timestamptz,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(author_id,client_request_id),
    CHECK((publication='PUBLISHED')=(published_at IS NOT NULL)),
    CHECK(status<>'TRANSFERRED' OR category='FOUND'),
    CHECK(category<>'NEIGHBOR_NEWS' OR status IN ('ACTIVE','CLOSED'))
);
CREATE INDEX community_posts_feed ON shelter.community_posts(published_at DESC,id DESC) WHERE publication='PUBLISHED' AND hidden_at IS NULL AND deleted_at IS NULL;
CREATE INDEX community_posts_author ON shelter.community_posts(author_id,created_at DESC,id DESC);
CREATE TABLE shelter.community_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES shelter.app_users(id),
    client_request_id uuid NOT NULL,
    content_hash char(64) NOT NULL,
    object_key varchar(200) NOT NULL UNIQUE,
    byte_size integer NOT NULL CHECK(byte_size BETWEEN 1 AND 5242880),
    state varchar(10) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','READY')),
    post_id uuid REFERENCES shelter.community_posts(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(owner_id,client_request_id)
);
CREATE INDEX community_media_owner ON shelter.community_media(owner_id,created_at DESC);
CREATE TABLE shelter.community_comments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid NOT NULL REFERENCES shelter.community_posts(id),
    author_id uuid NOT NULL REFERENCES shelter.app_users(id),
    client_request_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    kind varchar(10) NOT NULL CHECK(kind IN ('COMMENT','SIGHTING')),
    parent_id uuid,
    content jsonb NOT NULL CHECK(jsonb_typeof(content)='object'),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(post_id,author_id,client_request_id),
    UNIQUE(post_id,id),
    FOREIGN KEY(post_id,parent_id) REFERENCES shelter.community_comments(post_id,id),
    CHECK(parent_id IS NULL OR kind='COMMENT')
);
CREATE INDEX community_comments_page ON shelter.community_comments(post_id,created_at,id);
CREATE TABLE shelter.community_reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid NOT NULL REFERENCES shelter.community_posts(id),
    reporter_id uuid NOT NULL REFERENCES shelter.app_users(id),
    reason varchar(24) NOT NULL CHECK(reason IN ('SPAM','FALSE_INFORMATION','ABUSE','PRIVACY','OTHER')),
    details varchar(1000) NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','HIDDEN','DISMISSED')),
    version bigint NOT NULL DEFAULT 1,
    reviewed_by uuid REFERENCES shelter.app_users(id),
    reviewed_at timestamptz,
    review_note varchar(1000),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(post_id,reporter_id),
    CHECK((status='PENDING')=(reviewed_at IS NULL AND reviewed_by IS NULL))
);
CREATE INDEX community_reports_queue ON shelter.community_reports(status,created_at,id);
DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['community_posts','community_media','community_comments','community_reports'] LOOP
        EXECUTE format('ALTER TABLE shelter.%I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('REVOKE ALL ON shelter.%I FROM PUBLIC, anon, authenticated',t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON shelter.%I TO shelter_runtime',t);
        EXECUTE format('CREATE POLICY runtime_server_access ON shelter.%I TO shelter_runtime USING (true) WITH CHECK (true)',t);
    END LOOP;
END $$;
