-- Freeze selection and frontend interaction metadata with each generated pack.
-- NULL preserves the original full-eight-action jobs, including jobs still in progress.
ALTER TABLE shelter.asset_jobs ADD COLUMN behavior_plan jsonb;
ALTER TABLE shelter.asset_jobs ADD CHECK (behavior_plan IS NULL OR jsonb_typeof(behavior_plan)='object');
