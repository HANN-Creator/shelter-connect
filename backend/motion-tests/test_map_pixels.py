import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

HARNESS = Path(__file__).resolve().parents[1] / 'src/main/resources/motion-harness'
sys.path.insert(0, str(HARNESS))
from map_pixels import convert_sheet, source_palette


def dog(coat, outline):
    im = Image.new('RGBA', (64, 64))
    d = ImageDraw.Draw(im)
    d.rectangle((12, 20, 51, 53), fill=coat, outline=outline, width=2)
    d.rectangle((38, 8, 55, 30), fill=coat, outline=outline, width=2)
    d.rectangle((14, 48, 21, 59), fill=coat, outline=outline, width=2)
    d.rectangle((42, 48, 49, 59), fill=coat, outline=outline, width=2)
    d.rectangle((46, 14, 51, 19), fill=outline)
    d.point((49, 17), fill='#ffffff')
    return im


class MapPixelsTest(unittest.TestCase):
    def test_each_coat_retains_only_its_own_palette_and_binary_transparency(self):
        for coat, outline in [('#d7a256', '#513925'), ('#faf6ed', '#736e67'), ('#393939', '#121212'), ('#909090', '#202020')]:
            with self.subTest(coat=coat):
                base = dog(coat, outline)
                original = base.tobytes()
                result, meta = convert_sheet(base, base, 1)
                src = np.asarray(base); pixels = np.asarray(result)
                allowed = {tuple(p[:3]) for p in src.reshape(-1, 4) if p[3]}
                used = {tuple(p[:3]) for p in pixels.reshape(-1, 4) if p[3]}
                self.assertTrue(used <= allowed)
                # A 1/4-cell eye glint must not become a full white target pixel.
                self.assertNotEqual(result.getpixel((24, 8))[:3], (255, 255, 255))
                self.assertEqual(meta['converterVersion'], 'map-pixel-v2')
                self.assertEqual(set(np.unique(pixels[:, :, 3])), {0, 255})
                self.assertTrue((pixels[pixels[:, :, 3] == 0, :3] == 0).all())
                self.assertEqual(meta['anchorPixels'], {'x': 16, 'y': 30})
                self.assertEqual(result.getbbox()[3], 30)
                self.assertEqual(base.tobytes(), original)
                self.assertEqual(convert_sheet(base, base, 1)[0].tobytes(), result.tobytes())

    def test_palette_is_bounded_deterministic_and_does_not_invent_colors(self):
        a = np.zeros((64, 64, 4), dtype=np.uint8)
        for y in range(10, 60):
            for x in range(10, 54):
                a[y, x] = [x * 4, y * 4, (x + y) * 2, 255]
        base = Image.fromarray(a)
        palette = source_palette(base)
        self.assertEqual(len(palette), 16)
        self.assertTrue({tuple(c) for c in palette} <= {tuple(p[:3]) for p in a.reshape(-1, 4) if p[3]})
        self.assertEqual(convert_sheet(base, base, 1)[1], convert_sheet(base, base, 1)[1])

    def test_local_outline_shades_and_leg_gap_are_not_replaced_by_darkest_edge(self):
        base = dog('#faf6ed', '#736e67')
        d = ImageDraw.Draw(base)
        d.rectangle((38, 8, 55, 9), fill='#302820')  # darkest ear outline
        d.rectangle((14, 56, 15, 59), fill='#a29888')  # softer near paw edge
        result, _ = convert_sheet(base, base, 1)
        self.assertEqual(result.getpixel((19, 4)), (48, 40, 32, 255))
        self.assertEqual(result.getpixel((7, 29)), (162, 152, 136, 255))
        self.assertEqual(result.getpixel((15, 29)), (0, 0, 0, 0))

    def test_small_highlights_and_mouth_lines_keep_their_area_without_corner_bias(self):
        base = dog('#faf6ed', '#302820')
        d = ImageDraw.Draw(base)
        d.rectangle((20, 24, 27, 31), fill='#746960')  # intermediate source shade
        for point in [(0, 0), (1, 0), (0, 1), (1, 1)]:
            with self.subTest(point=point):
                source = base.copy(); draw = ImageDraw.Draw(source)
                draw.rectangle((46, 16, 47, 17), fill='#302820')
                draw.point((46 + point[0], 16 + point[1]), fill='#ffffff')
                draw.rectangle((40, 26, 41, 27), fill='#faf6ed')
                draw.point((40 + point[0], 26 + point[1]), fill='#302820')
                result, _ = convert_sheet(base, source, 1)
                self.assertEqual(result.getpixel((23, 8))[:3], (116, 105, 96))
                self.assertEqual(result.getpixel((20, 13))[:3], (250, 246, 237))

    def test_hidden_rgb_does_not_darken_edges_or_fill_transparent_gaps(self):
        base = dog('#faf6ed', '#736e67')
        a = np.asarray(base).copy()
        # Identical coverage with arbitrary hidden RGB must produce identical PNGs.
        a[a[:, :, 3] == 0, :3] = [255, 0, 255]
        changed, _ = convert_sheet(base, Image.fromarray(a), 1)
        original, _ = convert_sheet(base, base, 1)
        self.assertEqual(original.tobytes(), changed.tobytes())

    def test_all_frames_share_palette_and_keep_offsets_without_sheet_seam_bleed(self):
        base = dog('#d7a256', '#513925')
        shifted = Image.new('RGBA', (64, 64)); shifted.paste(base, (4, 0))
        source = Image.new('RGBA', (64 * 48, 64))
        for i in range(48): source.paste(base if i % 2 == 0 else shifted, (64 * i, 0))
        output, meta = convert_sheet(base, source, 48)
        self.assertEqual(output.size, (1536, 32))
        self.assertEqual(meta['frameCount'], 48)
        for i in range(48):
            expected, one = convert_sheet(base, base if i % 2 == 0 else shifted, 1)
            actual = output.crop((32 * i, 0, 32 * (i + 1), 32))
            self.assertEqual(expected.tobytes(), actual.tobytes())
            self.assertEqual(one['paletteSha256'], meta['paletteSha256'])
        self.assertEqual(output.crop((32, 0, 64, 32)).getbbox()[0] - output.crop((0, 0, 32, 32)).getbbox()[0], 2)

    def test_invalid_dimensions_empty_frames_and_unbounded_counts_are_rejected(self):
        base = dog('#eeeeee', '#444444')
        for count in [0, -1, 49, True, 1.0, '1']:
            with self.assertRaises(ValueError): convert_sheet(base, base, count)
        with self.assertRaises(ValueError): convert_sheet(base, base.resize((32, 32)), 1)
        with self.assertRaises(ValueError): convert_sheet(base, Image.new('RGBA', (64, 64)), 1)

    def test_bounded_runner_protocol_and_no_client_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); base = dog('#faf6ed', '#736e67')
            base.save(root / 'base.png'); base.save(root / 'source.png')
            for request, valid in [({'mode':'map_pixels','frameCount':1}, True),
                                   ({'mode':'map_pixels','frameCount':1,'source':'/tmp/secret'}, False),
                                   ({'mode':'map_pixels','frameCount':True}, False)]:
                (root / 'request.json').write_text(json.dumps(request))
                result = subprocess.run([sys.executable, str(HARNESS / 'runner.py'), temp], capture_output=True, timeout=10)
                self.assertEqual(result.returncode == 0, valid, result.stderr.decode())
                if valid:
                    with Image.open(root / 'sheet.png') as output: self.assertEqual(output.size, (32, 32))
                    self.assertTrue(json.loads((root / 'response.json').read_text())['paletteChecked'])


if __name__ == '__main__': unittest.main()
