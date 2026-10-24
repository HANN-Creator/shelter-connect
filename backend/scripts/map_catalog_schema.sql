-- Generated from the reviewed Notion archive by scripts/import_map_bundle.py.
-- Existing map_key values and publication flags are deliberately preserved.
CREATE TABLE shelter.map_templates (
    id varchar(64) NOT NULL CHECK (id ~ '^[a-z0-9-]+$'),
    version varchar(32) NOT NULL CHECK (version ~ '^[0-9]+\.[0-9]+\.[0-9]+$'),
    title varchar(120) NOT NULL,
    width integer NOT NULL CHECK (width > 0),
    height integer NOT NULL CHECK (height > 0),
    default_order integer NOT NULL CHECK (default_order >= 0),
    auto_assign boolean NOT NULL DEFAULT true,
    source_sha256 varchar(64) NOT NULL CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    manifest jsonb NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    layout jsonb NOT NULL CHECK (jsonb_typeof(layout) = 'object'),
    files jsonb NOT NULL CHECK (jsonb_typeof(files) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, version)
);
CREATE UNIQUE INDEX map_auto_assign_id ON shelter.map_templates(id) WHERE auto_assign;
CREATE UNIQUE INDEX map_auto_assign_order ON shelter.map_templates(default_order) WHERE auto_assign;

CREATE TABLE shelter.shelter_maps (
    shelter_id uuid NOT NULL REFERENCES shelter.shelters(id) ON DELETE CASCADE,
    map_id varchar(64) NOT NULL,
    map_version varchar(32) NOT NULL,
    sort_order integer NOT NULL CHECK (sort_order >= 0),
    is_default boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (shelter_id, map_id),
    FOREIGN KEY (map_id, map_version) REFERENCES shelter.map_templates(id, version),
    UNIQUE (shelter_id, sort_order)
);
CREATE UNIQUE INDEX shelter_map_default ON shelter.shelter_maps(shelter_id) WHERE is_default;
ALTER TABLE shelter.map_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE shelter.shelter_maps ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON shelter.map_templates, shelter.shelter_maps FROM PUBLIC, anon, authenticated;
GRANT SELECT ON shelter.map_templates, shelter.shelter_maps TO shelter_runtime;
CREATE POLICY runtime_map_read ON shelter.map_templates FOR SELECT TO shelter_runtime USING (true);
CREATE POLICY runtime_shelter_map_read ON shelter.shelter_maps FOR SELECT TO shelter_runtime USING (true);

-- MAP_SEEDS

-- One shared copy of each map; every existing shelter can use all three.
INSERT INTO shelter.shelter_maps(shelter_id,map_id,map_version,sort_order,is_default)
SELECT s.id,m.id,m.version,m.default_order,m.default_order=0
FROM shelter.shelters s CROSS JOIN shelter.map_templates m WHERE m.auto_assign;

-- Covers manual registrations AND public-data shelter creation. Runtime cannot
-- edit the global catalog or another shelter's associations directly.
CREATE FUNCTION shelter.assign_default_maps() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
BEGIN
    INSERT INTO shelter.shelter_maps(shelter_id,map_id,map_version,sort_order,is_default)
    SELECT NEW.id,m.id,m.version,m.default_order,m.default_order=0
    FROM shelter.map_templates m WHERE m.auto_assign;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION shelter.assign_default_maps() FROM PUBLIC, anon, authenticated, shelter_runtime;
CREATE TRIGGER shelter_default_maps AFTER INSERT ON shelter.shelters
FOR EACH ROW EXECUTE FUNCTION shelter.assign_default_maps();
