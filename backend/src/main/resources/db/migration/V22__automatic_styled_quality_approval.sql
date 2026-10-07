-- New jobs may be approved by the quality pipeline. Existing jobs/data are not opted in.
ALTER TABLE shelter.asset_jobs ADD COLUMN quality_approval jsonb;
ALTER TABLE shelter.asset_jobs ADD CONSTRAINT asset_jobs_quality_approval_object
    CHECK (quality_approval IS NULL OR jsonb_typeof(quality_approval)='object');
ALTER TABLE shelter.asset_jobs DROP CONSTRAINT asset_jobs_check;
ALTER TABLE shelter.asset_jobs ADD CONSTRAINT asset_jobs_approval_evidence CHECK (
    status <> 'APPROVED' OR (reviewed_at IS NOT NULL AND (
        (reviewed_by IS NOT NULL AND quality_approval IS NULL)
        OR (reviewed_by IS NULL AND COALESCE(
            pipeline_version='cozy32-photo-style-v1'
            AND quality_policy->>'automaticApproval'='quality-auto-approval-v1'
            AND COALESCE(quality_policy->>'referenceOnly','false')<>'true'
            AND quality_approval->>'version'='quality-auto-approval-v1'
            AND quality_approval->>'actor'='SYSTEM'
            AND quality_approval->>'decision'='APPROVE'
            AND quality_approval->>'stage'='FINAL'
            AND quality_approval->>'jobId'=id::text
            AND quality_approval->>'approvedAt' IS NOT NULL
            AND quality_approval->>'rulesSha256'=quality_policy->>'rulesSha256'
            AND quality_approval->'actionPlan'=action_plan
            AND jsonb_typeof(quality_approval->'steps')='array'
            AND jsonb_array_length(quality_approval->'steps')=1+4*(jsonb_array_length(action_plan)-1)
            AND quality_approval->'seedReview'=seed_review, false))
    ))
);
