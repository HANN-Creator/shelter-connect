-- Preserve every source rule and both AI results. Retries reuse a verified prompt;
-- interrupted model calls are never silently repeated and never purchase an image.
CREATE TABLE shelter.styled_lesson_prompts (
 job_id uuid NOT NULL,
 label varchar(32) NOT NULL,
 input_sha256 varchar(64) NOT NULL,
 input jsonb NOT NULL,
 lessons_sha256 varchar(64) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('COMPOSING','VALIDATING','READY','FAILED')),
 lease_token uuid NOT NULL,
 draft jsonb,
 validation jsonb,
 history jsonb NOT NULL DEFAULT '[]'::jsonb,
 description_sha256 varchar(64),
 failure_code varchar(100),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(job_id,label,input_sha256),
 FOREIGN KEY(job_id,label) REFERENCES shelter.styled_asset_steps(job_id,label) ON DELETE CASCADE
);
ALTER TABLE shelter.styled_lesson_prompts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON shelter.styled_lesson_prompts FROM PUBLIC;
DO $$ DECLARE r text; BEGIN
 FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
  IF EXISTS(SELECT FROM pg_roles WHERE rolname=r) THEN EXECUTE format('REVOKE ALL ON shelter.styled_lesson_prompts FROM %I',r); END IF;
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON shelter.styled_lesson_prompts TO shelter_runtime;
CREATE POLICY runtime_server_access ON shelter.styled_lesson_prompts TO shelter_runtime USING(true) WITH CHECK(true);
