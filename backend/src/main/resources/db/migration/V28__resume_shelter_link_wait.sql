-- A worker can park an unlinked public dog without buying generation.
-- Only that unleased wait permits linking; all other active jobs still block a move.
CREATE OR REPLACE FUNCTION shelter.link_public_animal(p_dog uuid,p_version timestamptz,p_record jsonb,p_fetched timestamptz)
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
        AND (status IN ('RUNNING','OUTCOME_UNKNOWN')
            OR (status='QUEUED' AND failure_code IS DISTINCT FROM 'PUBLIC_SOURCE_LINK_REQUIRED') OR lease_until>now())) THEN
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
    UPDATE shelter.asset_jobs SET failure_code=NULL,next_run_at=now()
        WHERE dog_id=p_dog AND status='QUEUED' AND failure_code='PUBLIC_SOURCE_LINK_REQUIRED'
          AND (lease_until IS NULL OR lease_until<=now());
    RETURN target;
END $$;
REVOKE ALL ON FUNCTION shelter.link_public_animal(uuid,timestamptz,jsonb,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION shelter.link_public_animal(uuid,timestamptz,jsonb,timestamptz) TO shelter_runtime;
