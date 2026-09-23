-- Existing jobs retain the old nine-step plan. New jobs snapshot only selected actions.
ALTER TABLE shelter.asset_jobs ADD COLUMN action_plan jsonb NOT NULL DEFAULT
    '["BASE","IDLE","WALK","RUN","SNIFF","TAIL_WAG","BACK_OFF","SIT","LIE_DOWN"]';
ALTER TABLE shelter.asset_jobs ADD COLUMN behavior_revision integer;
ALTER TABLE shelter.asset_jobs ADD COLUMN selection_key varchar(80) NOT NULL DEFAULT 'legacy';
ALTER TABLE shelter.asset_jobs ADD COLUMN rig_profile jsonb;
ALTER TABLE shelter.asset_jobs ADD COLUMN rig_revision integer NOT NULL DEFAULT 0 CHECK (rig_revision>=0);
ALTER TABLE shelter.asset_jobs ADD COLUMN rig_base_sha256 varchar(64);
ALTER TABLE shelter.asset_jobs ADD COLUMN rig_confirmed_by uuid REFERENCES shelter.app_users(id);
ALTER TABLE shelter.asset_jobs ADD COLUMN rig_confirmed_at timestamptz;
ALTER TABLE shelter.asset_jobs DROP CONSTRAINT asset_jobs_photo_id_pipeline_version_key;
ALTER TABLE shelter.asset_jobs ADD UNIQUE(photo_id,pipeline_version,selection_key);
ALTER TABLE shelter.asset_jobs DROP CONSTRAINT asset_jobs_status_check;
ALTER TABLE shelter.asset_jobs ADD CHECK (status IN
    ('QUEUED','RUNNING','RIG_REVIEW','REVIEW','APPROVED','REJECTED','FAILED','OUTCOME_UNKNOWN','CANCELLED'));
ALTER TABLE shelter.asset_jobs ADD CHECK (jsonb_typeof(action_plan)='array' AND jsonb_array_length(action_plan) BETWEEN 3 AND 9);
ALTER TABLE shelter.asset_jobs ADD CHECK ((rig_confirmed_at IS NULL)=(rig_confirmed_by IS NULL));
ALTER TABLE shelter.asset_steps DROP CONSTRAINT asset_steps_status_check;
ALTER TABLE shelter.asset_steps ADD CHECK (status IN
    ('PENDING','SUBMITTING','WAITING','RENDERING','SUCCEEDED','FAILED','OUTCOME_UNKNOWN'));
