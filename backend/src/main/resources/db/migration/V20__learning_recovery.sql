-- A single extra attempt may use newly validated lessons. Existing counts and
-- originals remain intact; pending learning never submits a provider request.
CREATE TABLE shelter.styled_learning_recoveries (
 job_id uuid NOT NULL,
 label varchar(32) NOT NULL,
 state varchar(24) NOT NULL DEFAULT 'WAITING_EVIDENCE'
  CHECK (state IN ('WAITING_EVIDENCE','WAITING_RULE','CHECKING_REFERENCE','QUEUED','COMPLETED','EXHAUSTED','NEEDS_REVIEW','FAILED','STALE','DISABLED')),
 rules_sha256 varchar(64) NOT NULL,
 seed_hashes jsonb NOT NULL,
 source_sha256 varchar(64) NOT NULL,
 source_repair_count integer NOT NULL,
 request_id uuid,
 request_sha256 varchar(64),
 required_lessons jsonb NOT NULL DEFAULT '[]',
 reference_result jsonb,
 reference_report jsonb,
 reason varchar(100),
 lease_token uuid,
 lease_until timestamptz,
 next_run_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(job_id,label),
 FOREIGN KEY(job_id,label) REFERENCES shelter.styled_asset_steps(job_id,label) ON DELETE CASCADE
);
CREATE INDEX styled_learning_recovery_work ON shelter.styled_learning_recoveries(state,next_run_at);
ALTER TABLE shelter.styled_learning_recoveries ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON shelter.styled_learning_recoveries FROM PUBLIC;
DO $$ DECLARE r text; BEGIN
 FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
  IF EXISTS (SELECT FROM pg_roles WHERE rolname=r) THEN EXECUTE format('REVOKE ALL ON shelter.styled_learning_recoveries FROM %I',r); END IF;
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON shelter.styled_learning_recoveries TO shelter_runtime;
CREATE POLICY runtime_server_access ON shelter.styled_learning_recoveries TO shelter_runtime USING(true) WITH CHECK(true);
ALTER TABLE shelter.styled_asset_steps DROP CONSTRAINT styled_asset_steps_repair_count_check;
ALTER TABLE shelter.styled_asset_steps ADD CONSTRAINT styled_asset_steps_repair_count_check
 CHECK (repair_count BETWEEN 0 AND 3 OR (action='IDLE' AND repair_count BETWEEN 4 AND 5));
