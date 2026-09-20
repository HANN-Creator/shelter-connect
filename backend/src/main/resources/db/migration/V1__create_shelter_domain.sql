-- Private application schema. Flyway creates `shelter`; no objects are added to public/auth/storage.
CREATE TABLE shelter.app_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name varchar(80) NOT NULL CHECK (btrim(display_name) <> ''),
    role varchar(16) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'OPERATOR')),
    auth_provider varchar(40),
    auth_subject varchar(255),
    disabled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_auth_pair CHECK (
        (auth_provider IS NULL AND auth_subject IS NULL) OR
        (auth_provider IS NOT NULL AND auth_subject IS NOT NULL
            AND btrim(auth_provider) <> '' AND btrim(auth_subject) <> '')
    ),
    UNIQUE (auth_provider, auth_subject)
);

CREATE TABLE shelter.shelters (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(120) NOT NULL CHECK (btrim(name) <> ''),
    region varchar(100) NOT NULL CHECK (btrim(region) <> ''),
    address text,
    latitude numeric(9,6) CHECK (latitude BETWEEN -90 AND 90),
    longitude numeric(9,6) CHECK (longitude BETWEEN -180 AND 180),
    contact_phone varchar(40),
    website_url text,
    map_key varchar(100),
    approval_status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    is_public boolean NOT NULL DEFAULT false,
    reviewed_by uuid REFERENCES shelter.app_users(id),
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT shelter_coordinate_pair CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT shelter_public_approval CHECK (NOT is_public OR approval_status = 'APPROVED'),
    CONSTRAINT shelter_approval_record CHECK (
        approval_status <> 'APPROVED' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

CREATE TABLE shelter.shelter_memberships (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES shelter.app_users(id),
    shelter_id uuid NOT NULL REFERENCES shelter.shelters(id),
    role varchar(16) NOT NULL DEFAULT 'STAFF' CHECK (role IN ('MANAGER', 'STAFF')),
    status varchar(16) NOT NULL DEFAULT 'INVITED' CHECK (status IN ('INVITED', 'ACTIVE', 'REVOKED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, shelter_id)
);

CREATE TABLE shelter.dogs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shelter_id uuid NOT NULL REFERENCES shelter.shelters(id),
    name varchar(80) NOT NULL CHECK (btrim(name) <> ''),
    sex varchar(10) NOT NULL DEFAULT 'UNKNOWN' CHECK (sex IN ('MALE', 'FEMALE', 'UNKNOWN')),
    breed varchar(120),
    birth_date date CHECK (isfinite(birth_date)),
    birth_date_precision varchar(10) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (birth_date_precision IN ('UNKNOWN', 'YEAR', 'MONTH', 'DAY')),
    birth_date_estimated boolean,
    weight_kg numeric(6,2) CHECK (weight_kg > 0 AND weight_kg < 10000),
    neutered boolean,
    adoption_status varchar(16) NOT NULL DEFAULT 'PAUSED'
        CHECK (adoption_status IN ('AVAILABLE', 'IN_PROGRESS', 'ADOPTED', 'PAUSED')),
    is_public boolean NOT NULL DEFAULT false,
    avatar_key varchar(100) NOT NULL CHECK (btrim(avatar_key) <> ''),
    trait_labels text[] NOT NULL DEFAULT '{}',
    introduction text,
    archived_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT dog_birth_precision CHECK (
        (birth_date_precision = 'UNKNOWN' AND birth_date IS NULL AND birth_date_estimated IS NULL) OR
        (birth_date_precision <> 'UNKNOWN' AND birth_date IS NOT NULL AND birth_date_estimated IS NOT NULL
            AND (birth_date_precision <> 'YEAR' OR to_char(birth_date, 'MM-DD') = '01-01')
            AND (birth_date_precision <> 'MONTH' OR extract(day FROM birth_date) = 1))
    ),
    CONSTRAINT dog_trait_labels CHECK (
        cardinality(trait_labels) <= 8 AND array_position(trait_labels, NULL) IS NULL
    )
);

CREATE TABLE shelter.dog_observations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dog_id uuid NOT NULL REFERENCES shelter.dogs(id),
    category varchar(24) NOT NULL CHECK (category IN
        ('TEMPERAMENT', 'ROUTINE', 'PEOPLE', 'DOGS', 'PLAY', 'CARE', 'HEALTH', 'OTHER')),
    content text NOT NULL CHECK (btrim(content) <> ''),
    observed_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL REFERENCES shelter.app_users(id),
    source_note text,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED', 'RETRACTED')),
    confirmed_by uuid REFERENCES shelter.app_users(id),
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, dog_id),
    CONSTRAINT observation_confirmation CHECK (
        status <> 'CONFIRMED' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)
    )
);

CREATE TABLE shelter.dog_photos (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dog_id uuid NOT NULL REFERENCES shelter.dogs(id),
    storage_bucket varchar(100) NOT NULL CHECK (btrim(storage_bucket) <> ''),
    storage_key text NOT NULL CHECK (btrim(storage_key) <> ''),
    sort_order integer NOT NULL CHECK (sort_order >= 0),
    caption text,
    source_note text,
    rights_status varchar(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (rights_status IN ('UNKNOWN', 'GRANTED', 'REVOKED')),
    rights_note text,
    rights_confirmed_by uuid REFERENCES shelter.app_users(id),
    rights_confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (dog_id, sort_order),
    UNIQUE (dog_id, storage_bucket, storage_key),
    CONSTRAINT photo_permission_record CHECK (
        rights_status <> 'GRANTED' OR
        (rights_note IS NOT NULL AND btrim(rights_note) <> ''
            AND rights_confirmed_by IS NOT NULL AND rights_confirmed_at IS NOT NULL)
    )
);

-- B-10 will define the versioned settings contract for the eight prebuilt animations.
CREATE TABLE shelter.dog_behavior_profiles (
    dog_id uuid PRIMARY KEY REFERENCES shelter.dogs(id),
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    settings jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(settings) = 'object'),
    source varchar(24) NOT NULL DEFAULT 'SHELTER' CHECK (source IN ('SHELTER', 'AI_SUGGESTED')),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED')),
    confirmed_by uuid REFERENCES shelter.app_users(id),
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT behavior_confirmation CHECK (
        status <> 'CONFIRMED' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)
    )
);

CREATE TABLE shelter.chat_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES shelter.app_users(id),
    dog_id uuid NOT NULL REFERENCES shelter.dogs(id),
    status varchar(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, dog_id)
);

CREATE TABLE shelter.chat_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL,
    dog_id uuid NOT NULL,
    role varchar(10) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content text NOT NULL CHECK (btrim(content) <> '' AND char_length(content) <= 12000),
    client_message_id varchar(128),
    reply_to_message_id uuid,
    processing_status varchar(16) NOT NULL CHECK (processing_status IN ('PENDING', 'COMPLETED', 'FAILED')),
    failure_code varchar(64),
    needs_shelter_confirmation boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, dog_id),
    UNIQUE (id, session_id, dog_id),
    UNIQUE (session_id, client_message_id),
    UNIQUE (reply_to_message_id),
    FOREIGN KEY (session_id, dog_id) REFERENCES shelter.chat_sessions(id, dog_id),
    FOREIGN KEY (reply_to_message_id, session_id, dog_id)
        REFERENCES shelter.chat_messages(id, session_id, dog_id),
    CONSTRAINT message_role_fields CHECK (
        (role = 'USER' AND client_message_id IS NOT NULL AND btrim(client_message_id) <> ''
            AND reply_to_message_id IS NULL AND NOT needs_shelter_confirmation) OR
        (role = 'ASSISTANT' AND client_message_id IS NULL AND reply_to_message_id IS NOT NULL
            AND processing_status = 'COMPLETED')
    ),
    CONSTRAINT message_failure_code CHECK (
        (processing_status = 'FAILED' AND failure_code IS NOT NULL AND btrim(failure_code) <> '') OR
        (processing_status <> 'FAILED' AND failure_code IS NULL)
    )
);

CREATE TABLE shelter.chat_message_observations (
    message_id uuid NOT NULL,
    observation_id uuid NOT NULL,
    dog_id uuid NOT NULL,
    observation_snapshot text NOT NULL CHECK (btrim(observation_snapshot) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, observation_id),
    FOREIGN KEY (message_id, dog_id) REFERENCES shelter.chat_messages(id, dog_id),
    FOREIGN KEY (observation_id, dog_id) REFERENCES shelter.dog_observations(id, dog_id)
);

CREATE TABLE shelter.adoption_notes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES shelter.app_users(id),
    dog_id uuid NOT NULL REFERENCES shelter.dogs(id),
    questions text NOT NULL DEFAULT '',
    care_plan text NOT NULL DEFAULT '',
    checklist jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(checklist) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, dog_id)
);

CREATE INDEX shelters_public_region_idx ON shelter.shelters(region, id)
    WHERE is_public AND approval_status = 'APPROVED';
CREATE INDEX memberships_shelter_idx ON shelter.shelter_memberships(shelter_id, status);
CREATE INDEX dogs_shelter_list_idx ON shelter.dogs(shelter_id, created_at, id)
    WHERE is_public AND archived_at IS NULL;
CREATE INDEX observations_dog_status_idx ON shelter.dog_observations(dog_id, status, observed_at, id);
CREATE INDEX sessions_user_idx ON shelter.chat_sessions(user_id, updated_at, id);
CREATE INDEX sessions_dog_idx ON shelter.chat_sessions(dog_id);
CREATE INDEX messages_session_order_idx ON shelter.chat_messages(session_id, created_at, id);
CREATE INDEX message_observations_observation_idx ON shelter.chat_message_observations(observation_id);
CREATE INDEX adoption_notes_dog_idx ON shelter.adoption_notes(dog_id);

CREATE FUNCTION shelter.touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION shelter.keep_session_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.user_id, NEW.dog_id) IS DISTINCT FROM (OLD.id, OLD.user_id, OLD.dog_id) THEN
        RAISE EXCEPTION 'A chat session cannot change its identity, user, or dog' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER session_identity BEFORE UPDATE ON shelter.chat_sessions
    FOR EACH ROW EXECUTE FUNCTION shelter.keep_session_identity();

CREATE FUNCTION shelter.check_message_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.id, NEW.session_id, NEW.dog_id, NEW.role, NEW.client_message_id, NEW.reply_to_message_id)
            IS DISTINCT FROM
            (OLD.id, OLD.session_id, OLD.dog_id, OLD.role, OLD.client_message_id, OLD.reply_to_message_id) THEN
            RAISE EXCEPTION 'Message routing and request identity cannot change' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.role = 'ASSISTANT' AND NOT EXISTS (
        SELECT 1 FROM shelter.chat_messages
        WHERE id = NEW.reply_to_message_id AND session_id = NEW.session_id
            AND dog_id = NEW.dog_id AND role = 'USER'
    ) THEN
        RAISE EXCEPTION 'An assistant response must reference a user message in the same session'
            USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER message_identity BEFORE INSERT OR UPDATE ON shelter.chat_messages
    FOR EACH ROW EXECUTE FUNCTION shelter.check_message_identity();

-- Default deny for browser/mobile DB access. Application-owner JDBC access remains available.
DO $$
DECLARE
    table_name text;
    api_role text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'app_users', 'shelters', 'shelter_memberships', 'dogs', 'dog_observations',
        'dog_photos', 'dog_behavior_profiles', 'chat_sessions', 'chat_messages', 'adoption_notes'
    ] LOOP
        EXECUTE format('CREATE TRIGGER set_updated_at BEFORE UPDATE ON shelter.%I '
            'FOR EACH ROW EXECUTE FUNCTION shelter.touch_updated_at()', table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY[
        'app_users', 'shelters', 'shelter_memberships', 'dogs', 'dog_observations',
        'dog_photos', 'dog_behavior_profiles', 'chat_sessions', 'chat_messages',
        'chat_message_observations', 'adoption_notes'
    ] LOOP
        EXECUTE format('ALTER TABLE shelter.%I ENABLE ROW LEVEL SECURITY', table_name);
    END LOOP;
    REVOKE ALL ON SCHEMA shelter FROM PUBLIC;
    REVOKE ALL ON ALL TABLES IN SCHEMA shelter FROM PUBLIC;
    REVOKE ALL ON ALL FUNCTIONS IN SCHEMA shelter FROM PUBLIC;
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            EXECUTE format('REVOKE ALL ON SCHEMA shelter FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA shelter FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA shelter FROM %I', api_role);
        END IF;
    END LOOP;
END;
$$;
