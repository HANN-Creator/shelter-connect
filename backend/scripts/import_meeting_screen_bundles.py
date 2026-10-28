"""Offline, repeatable import of the two approved B-110 design archives.

Never connects to a DB, executes archive code, or replaces a prior asset version.
Usage: python3 scripts/import_meeting_screen_bundles.py COURTYARD.zip LOADING.zip
"""
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
TARGET = BACKEND / "src/main/resources/map-assets"
VERSION = "2.0.0"
ARCHIVES = (
    "4a0b4d2f2527abf1fd9104615807406f0d0055b08f86ee2ea5adecb518a12b0f",
    "055e75e99b5aef234d2a5b731fd43c4ef61c7ff2d5d1121cb8f95d985dd7f993",
)


def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def sql_json(value):
    return "'" + json.dumps(value, ensure_ascii=False, separators=(",", ":")).replace("'", "''") + "'::jsonb"


def import_bundles(courtyard, loading):
    index = json.loads((TARGET / "index.json").read_text())
    seeds = []
    for number, path in enumerate((courtyard, loading)):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != ARCHIVES[number]:
            raise ValueError("Archive is not the approved design export")
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError("Corrupt archive")
            if number == 0:
                scene_id, title, width, height = "dog-meeting-courtyard", "둘만의 정원", 1024, 1792
                names = ["spritesheet.png", "spritesheet.json", "scene.json", "catalog.json"]
                catalog = json.loads(archive.read("catalog.json"))
                names.extend(sprite["file"] for sprite in catalog["sprites"])
                if len(catalog["sprites"]) != 156:
                    raise ValueError("Expected the approved 156-sprite scene")
                assets = {name: archive.read(name) for name in names}
                layout = json.loads(assets["scene.json"])
                manifest = dict(layout="scene.json", catalog="catalog.json", atlas="spritesheet.png",
                                atlasMetadata="spritesheet.json",
                                spriteCount=156, assetType="static_sprite_pack",
                                selectedDog={"source": "/v1/dogs/{dogId}/assets", "direction": "DOWN",
                                             "preferredActions": ["IDLE"], "fallbackImage": "baseUrl",
                                             "placement": {"coordinateSpace": "scene pixels", "anchorX": 512,
                                                           "anchorY": 914.49, "maxWidth": 149.05, "maxHeight": 208.21},
                                             "anchor": "use selected asset anchorPixels; preserve aspect ratio",
                                             "missingAsset": "environment only; never substitute another dog"})
            else:
                scene_id, title, width, height = "dog-meeting-loading", "둘만의 정원 · 로딩", 720, 1280
                rename = {"garden-back.spritesheet.png": "garden-back.png",
                          "garden-front.spritesheet.png": "garden-front.png",
                          "garden-back.spritesheet.json": "garden-back.json",
                          "garden-front.spritesheet.json": "garden-front.json"}
                names = [*rename, "background.png", "loading-paws.png", "dog-clip-mask.png", "animation.json"]
                assets = {}
                for name in names:
                    data = archive.read("courtyard-loading-sprites/" + name)
                    if name.endswith(".json"):
                        # Replace only complete filenames, including nested atlas metadata.
                        text = data.decode()
                        for old, new in rename.items():
                            text = text.replace('"' + old + '"', '"' + new + '"')
                        data = text.encode()
                    assets[rename.get(name, name)] = data
                layout = json.loads(assets["animation.json"])
                # The API supplies an exact anchor; do not override it with the design placeholder.
                layout["selectedDog"].pop("defaultSourceAnchor", None)
                layout["selectedDog"]["sourceAnchor"] = "use asset anchorPixels and frame dimensions"
                assets["animation.json"] = encode(layout)
                manifest = dict(layout="animation.json", assetType="layered_animation",
                                frameCount=120, fps=20, loopDurationMs=6000,
                                selectedDog={"source": "/v1/dogs/{dogId}/assets", "direction": "RIGHT",
                                             "preferredActions": ["RUN", "WALK"],
                                             "anchor": "use selected asset anchorPixels",
                                             "missingAsset": "environment only; never substitute another dog"})
            manifest.update(id=scene_id, version=VERSION, title=title, purpose="dog_meeting",
                            width=width, height=height, sampling="nearest", containsBakedDog=False,
                            sourceSha256=digest)
            assets["manifest.json"] = encode(manifest)
            files = {}
            for name, data in sorted(assets.items()):
                if not re.fullmatch(r"(?:[a-z0-9-]+/)*[a-z0-9-]+\.(?:png|json)", name):
                    raise ValueError("Unexpected runtime filename: " + name)
                key = f"{VERSION}/{scene_id}/{name}"
                output = TARGET / key
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(data)
                metadata = dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                                contentType="image/png" if name.endswith(".png") else "application/json")
                files[name] = metadata
                index[key] = metadata
            seeds.append("INSERT INTO shelter.map_templates(id,version,title,width,height,default_order,auto_assign,source_sha256,manifest,layout,files) VALUES ("
                         + f"'{scene_id}','{VERSION}','{title}',{width},{height},{3 + number},false,'{digest}',"
                         + ",".join(sql_json(value) for value in (manifest, layout, files)) + ");")
    (TARGET / "index.json").write_text(json.dumps(index, indent=2, sort_keys=True) + "\n")
    (BACKEND / "src/main/resources/db/migration/V31__meeting_screen_assets.sql").write_text(
        "-- B-110: approved reference-matched courtyard and dog-free loading, additive only.\n"
        "-- Existing v1 assets, shelter map links, dogs and publication settings are preserved.\n"
        + "\n".join(seeds) + "\n")
    print(f"Registered two v2 scenes; {len(index)} total runtime files")


if __name__ == "__main__":
    import_bundles(*(Path(arg) for arg in sys.argv[1:]))
