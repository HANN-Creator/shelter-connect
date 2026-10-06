-- Learned rules are additive data, never executable code or replacements for hard gates.
CREATE TABLE shelter.styled_quality_examples (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 job_id uuid NOT NULL,
 label varchar(32) NOT NULL,
 action varchar(16) NOT NULL,
 direction varchar(8) NOT NULL,
 tail varchar(8) NOT NULL,
 rules_sha256 varchar(64) NOT NULL CHECK (rules_sha256 ~ '^[a-f0-9]{64}$'),
 input_sha256 varchar(64) NOT NULL CHECK (input_sha256 ~ '^[a-f0-9]{64}$'),
 result jsonb NOT NULL,
 seeds jsonb NOT NULL,
 report jsonb NOT NULL,
 passed boolean NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(job_id,label) REFERENCES shelter.styled_asset_steps(job_id,label) ON DELETE CASCADE,
 UNIQUE(job_id,label,input_sha256,rules_sha256),
 CHECK (action IN ('IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN')),
 CHECK (direction IN ('south','north','west','east')),
 CHECK (tail IN ('LOW','LEVEL','HIGH','CURLED','UNKNOWN'))
);
CREATE INDEX styled_quality_example_scope ON shelter.styled_quality_examples(action,direction,tail,rules_sha256,passed,created_at DESC);
CREATE TABLE shelter.styled_quality_lessons (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 source_example_id uuid NOT NULL REFERENCES shelter.styled_quality_examples(id) ON DELETE CASCADE,
 source_job_id uuid NOT NULL REFERENCES shelter.asset_jobs(id) ON DELETE CASCADE,
 source_label varchar(32) NOT NULL,
 action varchar(16) NOT NULL,
 direction varchar(8) NOT NULL,
 tail varchar(8) NOT NULL,
 issue varchar(32) NOT NULL CHECK (issue IN ('CANVAS_CLIPPING','DIRECTION_DRIFT','TAIL_CARRIAGE','IDENTITY_DRIFT','ACTION_MISSING','DISCONTINUITY','DETACHED_PIXELS','IDLE_MOTION')),
 rules_sha256 varchar(64) NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'WAITING_EVIDENCE'
   CHECK (status IN ('WAITING_EVIDENCE','PROPOSING','CANDIDATE','VALIDATING','ACTIVE','REJECTED','FAILED','DISABLED','STALE')),
 candidate jsonb,
 candidate_sha256 varchar(64),
 validation_examples jsonb,
 validation jsonb,
 lease_token uuid,
 lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(source_job_id,source_label,issue,rules_sha256),
 CHECK ((status NOT IN ('CANDIDATE','VALIDATING','ACTIVE')) OR (candidate IS NOT NULL AND candidate_sha256 IS NOT NULL)),
 CHECK (status<>'ACTIVE' OR (validation IS NOT NULL AND validation->>'passed'='true'))
);
CREATE INDEX styled_quality_lesson_work ON shelter.styled_quality_lessons(status,created_at);
CREATE INDEX styled_quality_lesson_scope ON shelter.styled_quality_lessons(action,direction,tail,rules_sha256,status);
CREATE TABLE shelter.styled_quality_lesson_events (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 lesson_id uuid NOT NULL REFERENCES shelter.styled_quality_lessons(id) ON DELETE CASCADE,
 event varchar(32) NOT NULL,
 detail jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE shelter.styled_asset_steps ADD COLUMN learned_lessons jsonb NOT NULL DEFAULT '[]';
DO $$ DECLARE t text; r text; BEGIN
 FOREACH t IN ARRAY ARRAY['styled_quality_examples','styled_quality_lessons','styled_quality_lesson_events'] LOOP
  EXECUTE format('ALTER TABLE shelter.%I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('REVOKE ALL ON shelter.%I FROM PUBLIC',t);
  FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
   IF EXISTS (SELECT FROM pg_roles WHERE rolname=r) THEN EXECUTE format('REVOKE ALL ON shelter.%I FROM %I',t,r); END IF;
  END LOOP;
  EXECUTE format('GRANT SELECT,INSERT ON shelter.%I TO shelter_runtime',t);
  EXECUTE format('CREATE POLICY runtime_server_access ON shelter.%I TO shelter_runtime USING (true) WITH CHECK (true)',t);
 END LOOP;
END $$;
GRANT UPDATE ON shelter.styled_quality_lessons TO shelter_runtime;
