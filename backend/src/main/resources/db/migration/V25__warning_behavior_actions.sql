-- Additive action codes only. Do not rewrite profiles, plans, images or paid attempts.
ALTER TABLE shelter.asset_jobs DROP CONSTRAINT asset_jobs_action_plan_check;
ALTER TABLE shelter.asset_jobs ADD CONSTRAINT asset_jobs_action_plan_check CHECK (
  (COALESCE(quality_policy->>'referenceOnly','false') <> 'true'
    AND jsonb_typeof(action_plan)='array' AND jsonb_array_length(action_plan) BETWEEN 3 AND 11)
  OR (COALESCE(quality_policy->>'referenceOnly','false')='true' AND action_plan='["BASE"]'::jsonb AND status<>'APPROVED')
);
ALTER TABLE shelter.asset_steps DROP CONSTRAINT asset_steps_ordinal_check;
ALTER TABLE shelter.asset_steps ADD CONSTRAINT asset_steps_ordinal_check CHECK (ordinal BETWEEN 0 AND 10);
ALTER TABLE shelter.asset_steps DROP CONSTRAINT asset_steps_action_check;
ALTER TABLE shelter.asset_steps ADD CONSTRAINT asset_steps_action_check
 CHECK (action IN ('BASE','IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN','GROWL','WARNING_BARK'));
ALTER TABLE shelter.styled_asset_steps DROP CONSTRAINT styled_asset_steps_action_check;
ALTER TABLE shelter.styled_asset_steps ADD CONSTRAINT styled_asset_steps_action_check
 CHECK (action IN ('BASE','IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN','GROWL','WARNING_BARK'));
ALTER TABLE shelter.styled_asset_steps DROP CONSTRAINT styled_asset_steps_ordinal_check;
ALTER TABLE shelter.styled_asset_steps ADD CONSTRAINT styled_asset_steps_ordinal_check CHECK (ordinal BETWEEN 0 AND 40);

ALTER TABLE shelter.styled_quality_examples DROP CONSTRAINT styled_quality_examples_scope_check;
ALTER TABLE shelter.styled_quality_examples ADD CONSTRAINT styled_quality_examples_scope_check CHECK (
 (action='BASE' AND direction='all' AND tail='UNKNOWN') OR
 (action IN ('IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN','GROWL','WARNING_BARK') AND direction IN ('south','north','west','east'))
);

ALTER TABLE shelter.styled_quality_lessons DROP CONSTRAINT styled_quality_lessons_scope_check;
ALTER TABLE shelter.styled_quality_lessons ADD CONSTRAINT styled_quality_lessons_scope_check CHECK (
 (action='BASE' AND direction='all' AND tail='UNKNOWN' AND issue IN ('EYE_READABILITY','EYE_STYLE','EYE_DIRECTION','SEED_IDENTITY','CANVAS_CLIPPING','SEED_MOTION_MARGIN')) OR
 (action IN ('IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN','GROWL','WARNING_BARK') AND direction IN ('south','north','west','east') AND issue IN ('CANVAS_CLIPPING','DIRECTION_DRIFT','TAIL_CARRIAGE','IDENTITY_DRIFT','ACTION_MISSING','DISCONTINUITY','DETACHED_PIXELS','IDLE_MOTION'))
);

-- RLS, runtime grants and append-only evidence constraints are unchanged.
