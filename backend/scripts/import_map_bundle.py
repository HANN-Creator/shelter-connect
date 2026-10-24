"""Build the reviewed, immutable V29 map seed from the original Notion archive.

Offline developer tool only. Does not connect to a database or execute archive code.
"""
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import zipfile

ARCHIVE_SHA256 = "7eb2bbdc9f0172ff7fc1f2d5568ad6272fae6568b872ca974403b8317f09c552"
ROOT = "shelter-connect-three-maps/"
VERSION = "1.0.0"
BACKEND = Path(__file__).resolve().parents[1]


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def sql_json(value):
    return "'" + compact(value).replace("'", "''") + "'::jsonb"


def main(archive):
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == ARCHIVE_SHA256, "Unexpected archive"
    target = BACKEND / "src/main/resources/map-assets"
    index = {}
    seeds = []
    with zipfile.ZipFile(archive) as source:
        assert source.testzip() is None
        catalog = json.loads(source.read(ROOT + "manifest.json"))
        for order, item in enumerate(catalog["maps"]):
            map_id = item["id"]
            prefix = ROOT + "maps/" + map_id + "/"
            files = {}
            for name in sorted(source.namelist()):
                if not name.startswith(prefix):
                    continue
                relative = name.removeprefix(prefix)
                path = PurePosixPath(relative)
                if relative != "manifest.json" and (path.parts[0] not in ("map", "props", "animations") or path.suffix not in (".png", ".json")):
                    continue
                assert not path.is_absolute() and ".." not in path.parts
                data = source.read(name)
                key = f"{VERSION}/{map_id}/{relative}"
                output = target / key
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(data)
                entry = {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                         "contentType": "image/png" if path.suffix == ".png" else "application/json"}
                files[relative] = entry
                index[key] = entry
            manifest = json.loads(source.read(prefix + "manifest.json"))
            layout = json.loads(source.read(prefix + manifest["layout"]))
            seeds.append("INSERT INTO shelter.map_templates(id,version,title,width,height,default_order,source_sha256,manifest,layout,files) VALUES ("
                         + f"'{map_id}','{VERSION}','{item['title']}',{manifest['mapWidth']},{manifest['mapHeight']},{order},'{ARCHIVE_SHA256}',"
                         + ",".join(map(sql_json, (manifest, layout, files))) + ");")
    target.mkdir(parents=True, exist_ok=True)
    (target / "index.json").write_text(json.dumps(index, indent=2, sort_keys=True) + "\n")
    migration = BACKEND / "src/main/resources/db/migration/V29__shared_shelter_maps.sql"
    template = (BACKEND / "scripts/map_catalog_schema.sql").read_text()
    migration.write_text(template.replace("-- MAP_SEEDS", "\n".join(seeds)), encoding="utf-8")
    print(f"Imported {len(seeds)} maps / {len(index)} immutable runtime files; wrote V29")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
