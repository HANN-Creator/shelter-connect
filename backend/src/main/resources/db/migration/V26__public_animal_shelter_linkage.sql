-- Physical shelter identity and the existing asset-management scope are distinct.
-- Neither this migration nor source linking grants memberships or publishes animals.
ALTER TABLE shelter.shelters ADD COLUMN public_care_reg_no varchar(15);
CREATE UNIQUE INDEX shelter_public_care_identity ON shelter.shelters(public_care_reg_no)
    WHERE public_care_reg_no IS NOT NULL;
ALTER TABLE shelter.shelters ADD CONSTRAINT shelter_public_care_code
    CHECK (public_care_reg_no IS NULL OR public_care_reg_no ~ '^[0-9]{15}$');
ALTER TABLE shelter.dogs ADD COLUMN management_shelter_id uuid REFERENCES shelter.shelters(id);
CREATE INDEX dog_management_scope ON shelter.dogs((coalesce(management_shelter_id,shelter_id)),id);

CREATE TABLE shelter.public_animal_sources (
    dog_id uuid PRIMARY KEY REFERENCES shelter.dogs(id),
    animal_id varchar(15) NOT NULL UNIQUE CHECK (animal_id ~ '^[0-9]{15}$'),
    shelter_id uuid NOT NULL REFERENCES shelter.shelters(id),
    care_reg_no varchar(15) NOT NULL CHECK (care_reg_no ~ '^[0-9]{15}$'),
    source_record jsonb NOT NULL CHECK (jsonb_typeof(source_record)='object'),
    fetched_at timestamptz NOT NULL,
    linked_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE shelter.public_animal_sources ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON shelter.public_animal_sources FROM PUBLIC;
GRANT SELECT,INSERT,UPDATE ON shelter.public_animal_sources TO shelter_runtime;
CREATE POLICY runtime_public_animal_source ON shelter.public_animal_sources TO shelter_runtime
    USING (true) WITH CHECK (true);
-- The application can register private, unapproved source shelters, never approve them.
GRANT INSERT(name,region,address,contact_phone,public_care_reg_no) ON shelter.shelters TO shelter_runtime;

CREATE FUNCTION shelter.link_public_animal(p_dog uuid,p_version timestamptz,p_record jsonb,p_fetched timestamptz)
RETURNS uuid LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,shelter AS $$
DECLARE d shelter.dogs%ROWTYPE; target uuid; animal text; care text; old_animal text;
BEGIN
    animal:=p_record->>'desertionNo'; care:=p_record->>'careRegNo';
    IF animal IS NULL OR animal !~ '^[0-9]{15}$' OR care IS NULL OR care !~ '^[0-9]{15}$'
       OR coalesce(length(btrim(p_record->>'careNm')),0) NOT BETWEEN 1 AND 120
       OR coalesce(length(btrim(p_record->>'orgNm')),0) NOT BETWEEN 1 AND 100
       OR coalesce(length(btrim(p_record->>'careAddr')),0) NOT BETWEEN 1 AND 2000
       OR length(coalesce(p_record->>'careTel',''))>40
       OR p_fetched IS NULL OR p_fetched>now()+interval '5 minutes' THEN
        RAISE EXCEPTION 'PUBLIC_SOURCE_INVALID' USING ERRCODE='P0001';
    END IF;
    SELECT * INTO STRICT d FROM shelter.dogs WHERE id=p_dog FOR UPDATE;
    IF d.updated_at IS DISTINCT FROM p_version THEN
        RAISE EXCEPTION 'STALE_RESOURCE' USING ERRCODE='P0001';
    END IF;
    IF d.is_public OR d.archived_at IS NOT NULL THEN
        RAISE EXCEPTION 'PUBLIC_SOURCE_PRIVATE_REQUIRED' USING ERRCODE='P0001';
    END IF;
    SELECT animal_id INTO old_animal FROM shelter.public_animal_sources WHERE dog_id=p_dog;
    IF (old_animal IS NOT NULL AND old_animal<>animal)
       OR (old_animal IS NULL AND position('public animal '||animal||'.' in coalesce(d.introduction,''))=0) THEN
        RAISE EXCEPTION 'PUBLIC_SOURCE_IDENTITY_MISMATCH' USING ERRCODE='P0001';
    END IF;
    -- Lock every job before checking so no running generation changes ownership mid-flight.
    PERFORM id FROM shelter.asset_jobs WHERE dog_id=p_dog ORDER BY id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM shelter.asset_jobs WHERE dog_id=p_dog
        AND (status IN ('QUEUED','RUNNING','OUTCOME_UNKNOWN') OR lease_until>now())) THEN
        RAISE EXCEPTION 'PUBLIC_SOURCE_JOB_ACTIVE' USING ERRCODE='P0001';
    END IF;
    INSERT INTO shelter.shelters(name,region,address,contact_phone,public_care_reg_no)
        VALUES (btrim(p_record->>'careNm'),btrim(p_record->>'orgNm'),btrim(p_record->>'careAddr'),
                nullif(btrim(p_record->>'careTel'),''),care)
        ON CONFLICT (public_care_reg_no) WHERE public_care_reg_no IS NOT NULL DO NOTHING;
    SELECT id INTO STRICT target FROM shelter.shelters WHERE public_care_reg_no=care;
    INSERT INTO shelter.public_animal_sources(dog_id,animal_id,shelter_id,care_reg_no,source_record,fetched_at)
        VALUES (p_dog,animal,target,care,p_record,p_fetched)
        ON CONFLICT (dog_id) DO UPDATE SET shelter_id=excluded.shelter_id,care_reg_no=excluded.care_reg_no,
            source_record=excluded.source_record,fetched_at=excluded.fetched_at,linked_at=now();
    UPDATE shelter.dogs SET management_shelter_id=coalesce(d.management_shelter_id,d.shelter_id),
        shelter_id=target WHERE id=p_dog;
    RETURN target;
END $$;
REVOKE ALL ON FUNCTION shelter.link_public_animal(uuid,timestamptz,jsonb,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION shelter.link_public_animal(uuid,timestamptz,jsonb,timestamptz) TO shelter_runtime;
