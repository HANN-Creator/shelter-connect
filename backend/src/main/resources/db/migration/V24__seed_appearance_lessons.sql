-- Add BASE-only rendering/tail defects without changing existing data or motion scopes.
ALTER TABLE shelter.styled_quality_lessons DROP CONSTRAINT styled_quality_lessons_scope_check;
ALTER TABLE shelter.styled_quality_lessons ADD CONSTRAINT styled_quality_lessons_scope_check CHECK (
 (action='BASE' AND direction='all' AND tail='UNKNOWN' AND issue IN ('EYE_READABILITY','EYE_STYLE','EYE_DIRECTION','SEED_IDENTITY','CANVAS_CLIPPING','SEED_MOTION_MARGIN','SEED_STYLE','SEED_TAIL')) OR
 (action IN ('IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN') AND direction IN ('south','north','west','east') AND issue IN ('CANVAS_CLIPPING','DIRECTION_DRIFT','TAIL_CARRIAGE','IDENTITY_DRIFT','ACTION_MISSING','DISCONTINUITY','DETACHED_PIXELS','IDLE_MOTION'))
);
