CREATE TABLE shelter.behavior_suggestions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dog_id uuid NOT NULL REFERENCES shelter.dogs(id),
    client_request_id uuid NOT NULL,
    requested_by uuid NOT NULL REFERENCES shelter.app_users(id),
    expected_revision integer NOT NULL CHECK (expected_revision >= 0),
    evidence_ids jsonb NOT NULL,
    observations jsonb NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    result jsonb,
    failure_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (dog_id,client_request_id)
);
CREATE INDEX behavior_suggestions_created_idx ON shelter.behavior_suggestions(created_at);

CREATE TABLE shelter.photo_upload_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dog_id uuid NOT NULL REFERENCES shelter.dogs(id),
    client_upload_id uuid NOT NULL,
    photo_id uuid NOT NULL UNIQUE REFERENCES shelter.dog_photos(id),
    permission_id uuid NOT NULL REFERENCES shelter.asset_source_permissions(id),
    content_hash varchar(64) NOT NULL,
    rights_note text NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','COMPLETED')),
    lease_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (dog_id,client_upload_id)
);

ALTER TABLE shelter.behavior_suggestions ENABLE ROW LEVEL SECURITY;
ALTER TABLE shelter.photo_upload_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON shelter.behavior_suggestions,shelter.photo_upload_requests FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
        REVOKE ALL ON shelter.behavior_suggestions,shelter.photo_upload_requests FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
        REVOKE ALL ON shelter.behavior_suggestions,shelter.photo_upload_requests FROM authenticated;
    END IF;
END $$;
