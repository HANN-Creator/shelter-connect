"""Regression against the approved visual exports, without an AI/network call."""
import hashlib
import json
from pathlib import Path
import unittest
from PIL import Image

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/map-assets/2.0.0"


def read(root, name):
    return json.loads((root / name).read_text())


class MeetingScreenAssetsTest(unittest.TestCase):
    def test_courtyard_atlas_rebuilds_approved_background_exactly(self):
        root = ROOT / "dog-meeting-courtyard"
        layout, metadata = read(root, "scene.json"), read(root, "spritesheet.json")
        atlas = Image.open(root / metadata["meta"]["image"]).convert("RGBA")
        canvas = Image.new("RGBA", (layout["width"], layout["height"]))
        sprites = {s["id"]: s for s in read(root, "catalog.json")["sprites"]}
        self.assertEqual(len(sprites), 156)
        for layer in layout["layers"]:
            frame = metadata["frames"][layer["sprite"]]["frame"]
            x, y, w, h = (frame[key] for key in ("x", "y", "w", "h"))
            cut = atlas.crop((x, y, x + w, y + h))
            original = Image.open(root / sprites[layer["sprite"]]["file"]).convert("RGBA")
            self.assertEqual(cut.getchannel("A").tobytes(), original.getchannel("A").tobytes())
            # Atlas packing clears invisible RGB; only alpha and visible colors are meaningful.
            black = Image.new("RGBA", cut.size, (0, 0, 0, 255))
            self.assertEqual(Image.alpha_composite(black, cut).tobytes(),
                             Image.alpha_composite(black, original).tobytes())
            canvas.alpha_composite(cut, (layer["x"], layer["y"]))
        self.assertEqual(hashlib.sha256(canvas.tobytes()).hexdigest(),
                         "cec511ff8830580645116ce391f0ddec53bab38e316e36fbc61a7942beda72de")

    def test_loading_layers_rebuild_all_120_approved_dog_free_frames(self):
        root = ROOT / "dog-meeting-loading"
        layout = read(root, "animation.json")
        self.assertEqual(layout["renderOrder"], ["background", "gardenBack", "selectedDog", "gardenFront", "paws"])
        self.assertFalse(layout["containsBakedDog"])
        self.assertNotIn("defaultSourceAnchor", layout["selectedDog"])
        self.assertEqual(sum(f["durationMs"] for f in layout["timeline"]), 6000)
        self.assertEqual(len(layout["timeline"]), 120)
        back = Image.open(root / layout["layers"]["gardenBack"]["image"]).convert("RGBA")
        front = Image.open(root / layout["layers"]["gardenFront"]["image"]).convert("RGBA")
        self.assertEqual(back.size, (2880, 1880))
        combined = Image.alpha_composite(back, front)
        self.assertEqual(hashlib.sha256(combined.tobytes()).hexdigest(),
                         "8b900e9522187dfe703a8218f8a0aadc9dec30fe290e42a728c6dbbdb9a1efd0")
        for key in ("gardenBack", "gardenFront"):
            layer = layout["layers"][key]
            metadata = read(root, layer["image"].replace(".png", ".json"))
            self.assertEqual(metadata["meta"]["image"], layer["image"])
            for entry in layout["timeline"]:
                rect = metadata["frames"][entry["frame"]]["frame"]
                self.assertLessEqual(rect["x"] + rect["w"], back.width)
                self.assertLessEqual(rect["y"] + rect["h"], back.height)
        with Image.open(root / layout["selectedDog"]["clipMask"]) as mask:
            self.assertEqual(mask.size, (240, 188))

    def test_registered_manifests_only_reference_bundled_public_files(self):
        for root in ROOT.iterdir():
            manifest = read(root, "manifest.json")
            self.assertFalse(manifest["containsBakedDog"])
            self.assertEqual(manifest["selectedDog"]["source"], "/v1/dogs/{dogId}/assets")
            for key in ("layout", "catalog", "atlas", "atlasMetadata"):
                if key in manifest:
                    self.assertTrue((root / manifest[key]).is_file(), manifest[key])


if __name__ == "__main__":
    unittest.main()
