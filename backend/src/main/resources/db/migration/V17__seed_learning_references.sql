-- Imported learning references contain only BASE and can never become playable approved assets.
ALTER TABLE shelter.asset_jobs DROP CONSTRAINT asset_jobs_action_plan_check;
ALTER TABLE shelter.asset_jobs ADD CONSTRAINT asset_jobs_action_plan_check CHECK (
  (COALESCE(quality_policy->>'referenceOnly','false') <> 'true'
    AND jsonb_typeof(action_plan)='array' AND jsonb_array_length(action_plan) BETWEEN 3 AND 9)
  OR (COALESCE(quality_policy->>'referenceOnly','false')='true' AND action_plan='["BASE"]'::jsonb AND status<>'APPROVED')
);
