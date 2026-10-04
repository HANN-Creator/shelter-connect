-- Native 32px generation shares permissions, jobs and the paid submission ledger.
ALTER TABLE shelter.asset_jobs ADD COLUMN styled_input jsonb;
ALTER TABLE shelter.asset_jobs ADD COLUMN seed_review jsonb;
ALTER TABLE shelter.asset_jobs DROP CONSTRAINT asset_jobs_status_check;
ALTER TABLE shelter.asset_jobs ADD CHECK (status IN
 ('QUEUED','RUNNING','RIG_REVIEW','SEED_REVIEW','REVIEW','APPROVED','REJECTED','FAILED','OUTCOME_UNKNOWN','CANCELLED'));
CREATE TABLE shelter.styled_asset_steps (
 job_id uuid NOT NULL REFERENCES shelter.asset_jobs(id),
 label varchar(32) NOT NULL,
 ordinal integer NOT NULL CHECK (ordinal BETWEEN 0 AND 32),
 action varchar(16) NOT NULL CHECK (action IN ('BASE','IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN')),
 direction varchar(8) CHECK (direction IN ('south','north','west','east')),
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SUBMITTING','WAITING','PERSISTING','SUCCEEDED','FAILED','OUTCOME_UNKNOWN')),
 provider_job_id uuid UNIQUE,
 submitted_at timestamptz,
 request_sha256 varchar(64),
 provider_result jsonb,
 attempt_history jsonb NOT NULL DEFAULT '[]'::jsonb,
 result jsonb,
 PRIMARY KEY (job_id,label), UNIQUE(job_id,ordinal),
 CHECK ((action='BASE' AND direction IS NULL AND label='character' AND ordinal=0)
     OR (action<>'BASE' AND direction IS NOT NULL AND label=lower(action)||'-'||direction AND ordinal>0)),
 CHECK (status<>'SUCCEEDED' OR result IS NOT NULL),
 CHECK (status<>'WAITING' OR provider_job_id IS NOT NULL),
 CHECK (status<>'PERSISTING' OR provider_result IS NOT NULL)
);
ALTER TABLE shelter.styled_asset_steps ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON shelter.styled_asset_steps FROM PUBLIC;
DO $$ DECLARE r text; BEGIN
 FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
  IF EXISTS (SELECT FROM pg_roles WHERE rolname=r) THEN
   EXECUTE format('REVOKE ALL ON shelter.styled_asset_steps FROM %I',r);
  END IF;
 END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON shelter.styled_asset_steps TO shelter_runtime;
CREATE POLICY runtime_server_access ON shelter.styled_asset_steps TO shelter_runtime USING (true) WITH CHECK (true);
