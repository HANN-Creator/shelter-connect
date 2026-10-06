-- Seed lessons inspect a whole four-view set; motion scopes remain unchanged.
ALTER TABLE shelter.styled_quality_examples DROP CONSTRAINT styled_quality_examples_action_check;
ALTER TABLE shelter.styled_quality_examples DROP CONSTRAINT styled_quality_examples_direction_check;
ALTER TABLE shelter.styled_quality_examples ADD CONSTRAINT styled_quality_examples_scope_check CHECK (
 (action='BASE' AND direction='all' AND tail='UNKNOWN') OR
 (action IN ('IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN') AND direction IN ('south','north','west','east'))
);
ALTER TABLE shelter.styled_quality_lessons DROP CONSTRAINT styled_quality_lessons_issue_check;
ALTER TABLE shelter.styled_quality_lessons ADD CONSTRAINT styled_quality_lessons_scope_check CHECK (
 (action='BASE' AND direction='all' AND tail='UNKNOWN' AND issue IN ('EYE_READABILITY','EYE_STYLE','EYE_DIRECTION','SEED_IDENTITY','CANVAS_CLIPPING')) OR
 (action IN ('IDLE','WALK','RUN','SNIFF','TAIL_WAG','BACK_OFF','SIT','LIE_DOWN') AND direction IN ('south','north','west','east') AND issue IN ('CANVAS_CLIPPING','DIRECTION_DRIFT','TAIL_CARRIAGE','IDENTITY_DRIFT','ACTION_MISSING','DISCONTINUITY','DETACHED_PIXELS','IDLE_MOTION'))
);
-- Existing append-only examples, RLS and runtime grants are retained.
