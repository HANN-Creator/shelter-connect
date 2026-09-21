-- Preserve existing draft settings. B-10 validates schema version 1 before confirmation/publication.
ALTER TABLE shelter.dog_behavior_profiles ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision > 0);

CREATE TABLE shelter.dog_behavior_evidence (
    dog_id uuid NOT NULL REFERENCES shelter.dog_behavior_profiles(dog_id) ON DELETE CASCADE,
    observation_id uuid NOT NULL,
    PRIMARY KEY (dog_id, observation_id),
    FOREIGN KEY (observation_id, dog_id) REFERENCES shelter.dog_observations(id, dog_id)
);
CREATE INDEX behavior_evidence_observation_idx ON shelter.dog_behavior_evidence(observation_id);
ALTER TABLE shelter.dog_behavior_evidence ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON shelter.dog_behavior_evidence FROM PUBLIC;
DO $$
DECLARE api_role text;
BEGIN
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            EXECUTE format('REVOKE ALL ON shelter.dog_behavior_evidence FROM %I', api_role);
        END IF;
    END LOOP;
END;
$$;
