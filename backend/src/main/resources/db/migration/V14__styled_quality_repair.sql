-- Opt-in upgrade for existing packs; new packs record the quality policy at creation.
ALTER TABLE shelter.asset_jobs ADD COLUMN quality_policy jsonb;
ALTER TABLE shelter.styled_asset_steps ADD COLUMN quality_report jsonb;
ALTER TABLE shelter.styled_asset_steps ADD COLUMN repair_count integer NOT NULL DEFAULT 0 CHECK (repair_count BETWEEN 0 AND 2);
ALTER TABLE shelter.styled_asset_steps DROP CONSTRAINT styled_asset_steps_status_check;
ALTER TABLE shelter.styled_asset_steps ADD CHECK (status IN
 ('PENDING','SUBMITTING','WAITING','PERSISTING','CHECKING','SUCCEEDED','FAILED','OUTCOME_UNKNOWN'));
