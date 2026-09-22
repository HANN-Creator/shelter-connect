-- Every permission is explicit and revocable. No existing photo is opted in by this migration.
CREATE TABLE shelter.asset_source_permissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shelter_id uuid NOT NULL REFERENCES shelter.shelters(id),
    source_key varchar(160) NOT NULL CHECK (btrim(source_key) <> ''),
    source_kind varchar(16) NOT NULL CHECK (source_kind IN ('SHELTER','CRAWL')),
    permission_note varchar(2000) NOT NULL CHECK (btrim(permission_note) <> ''),
    crawl_allowed boolean NOT NULL DEFAULT false,
    derivatives_allowed boolean NOT NULL DEFAULT false,
    pixellab_allowed boolean NOT NULL DEFAULT false,
    auto_generate boolean NOT NULL DEFAULT false,
    recorded_by uuid NOT NULL REFERENCES shelter.app_users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    CHECK (NOT auto_generate OR (derivatives_allowed AND pixellab_allowed AND (source_kind <> 'CRAWL' OR crawl_allowed)))
);
CREATE UNIQUE INDEX asset_active_source ON shelter.asset_source_permissions(shelter_id,source_key) WHERE revoked_at IS NULL;
CREATE TABLE shelter.asset_photo_sources (
    photo_id uuid PRIMARY KEY REFERENCES shelter.dog_photos(id),
    permission_id uuid NOT NULL REFERENCES shelter.asset_source_permissions(id),
    storage_bucket varchar(100) NOT NULL,
    storage_key text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE shelter.asset_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    photo_id uuid NOT NULL REFERENCES shelter.asset_photo_sources(photo_id),
    dog_id uuid NOT NULL REFERENCES shelter.dogs(id),
    shelter_id uuid NOT NULL REFERENCES shelter.shelters(id),
    permission_id uuid NOT NULL REFERENCES shelter.asset_source_permissions(id),
    pipeline_version varchar(80) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'QUEUED' CHECK (status IN
        ('QUEUED','RUNNING','REVIEW','APPROVED','REJECTED','FAILED','OUTCOME_UNKNOWN','CANCELLED')),
    failure_code varchar(64),
    lease_token uuid,
    lease_until timestamptz,
    next_run_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    reviewed_by uuid REFERENCES shelter.app_users(id),
    reviewed_at timestamptz,
    UNIQUE (photo_id,pipeline_version),
    CHECK (status <> 'APPROVED' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE TABLE shelter.asset_steps (
    job_id uuid NOT NULL REFERENCES shelter.asset_jobs(id),
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 0 AND 8),
    action varchar(16) NOT NULL CHECK (action IN ('BASE','IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN')),
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN
        ('PENDING','SUBMITTING','WAITING','SUCCEEDED','FAILED','OUTCOME_UNKNOWN')),
    provider_job_id uuid UNIQUE,
    submitted_at timestamptz,
    result jsonb,
    PRIMARY KEY (job_id,action),
    UNIQUE (job_id,ordinal),
    CHECK (status <> 'SUCCEEDED' OR result IS NOT NULL),
    CHECK (status <> 'WAITING' OR provider_job_id IS NOT NULL)
);
CREATE INDEX asset_work_queue ON shelter.asset_jobs(next_run_at) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX asset_daily_submissions ON shelter.asset_steps(submitted_at) WHERE submitted_at IS NOT NULL;
CREATE TRIGGER set_updated_at BEFORE UPDATE ON shelter.asset_jobs FOR EACH ROW EXECUTE FUNCTION shelter.touch_updated_at();
DO $$ DECLARE t text; r text; BEGIN
    FOREACH t IN ARRAY ARRAY['asset_source_permissions','asset_photo_sources','asset_jobs','asset_steps'] LOOP
        EXECUTE format('ALTER TABLE shelter.%I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('REVOKE ALL ON shelter.%I FROM PUBLIC',t);
        FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=r) THEN
                EXECUTE format('REVOKE ALL ON shelter.%I FROM %I',t,r);
            END IF;
        END LOOP;
    END LOOP;
END $$;
CREATE TABLE shelter.asset_submissions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES shelter.asset_jobs(id),
    action varchar(16) NOT NULL,
    submitted_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX asset_submission_day ON shelter.asset_submissions(submitted_at);
ALTER TABLE shelter.asset_submissions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON shelter.asset_submissions FROM PUBLIC;
DO $$ DECLARE r text; BEGIN
    FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=r) THEN
            EXECUTE format('REVOKE ALL ON shelter.asset_submissions FROM %I',r);
        END IF;
    END LOOP;
END $$;
