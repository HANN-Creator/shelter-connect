-- One learned continuation also applies to walking/sitting. Never reset attempts.
ALTER TABLE shelter.styled_asset_steps DROP CONSTRAINT styled_asset_steps_repair_count_check;
ALTER TABLE shelter.styled_asset_steps ADD CONSTRAINT styled_asset_steps_repair_count_check
 CHECK (repair_count BETWEEN 0 AND 3 OR (action='IDLE' AND repair_count BETWEEN 4 AND 5)
   OR (action IN ('WALK','SIT') AND repair_count=4));

-- Rejected candidates retain their complete evidence in the append-only events.
ALTER TABLE shelter.styled_quality_lessons
 ADD COLUMN revision_count integer NOT NULL DEFAULT 0 CHECK (revision_count BETWEEN 0 AND 2),
 ADD COLUMN revision_feedback jsonb;
ALTER TABLE shelter.styled_quality_lessons ADD CONSTRAINT styled_lesson_revision_feedback_object
 CHECK (revision_feedback IS NULL OR jsonb_typeof(revision_feedback)='object');
ALTER TABLE shelter.styled_quality_lessons DROP CONSTRAINT styled_quality_lessons_scope_check;
ALTER TABLE shelter.styled_quality_lessons ADD CONSTRAINT styled_quality_lessons_scope_check CHECK (
 (action='BASE' AND direction='all' AND tail='UNKNOWN' AND issue IN ('EYE_READABILITY','EYE_STYLE','EYE_DIRECTION','SEED_IDENTITY','CANVAS_CLIPPING','SEED_MOTION_MARGIN')) OR
 (action IN ('IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN') AND direction IN ('south','north','west','east') AND issue IN ('CANVAS_CLIPPING','DIRECTION_DRIFT','TAIL_CARRIAGE','IDENTITY_DRIFT','ACTION_MISSING','DISCONTINUITY','DETACHED_PIXELS','IDLE_MOTION'))
);
