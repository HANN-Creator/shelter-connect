import sys
import unittest
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'src/main/resources/motion-harness'))
from limb_art import LimbArt, sample_cycle


class LimbArtTest(unittest.TestCase):
    def setUp(self):
        self.base = Image.new('RGBA', (64, 64))
        draw = ImageDraw.Draw(self.base)
        self.legs = {}
        for name, x in [('near', 16), ('far', 44)]:
            self.legs[name] = {'root': [x, 20], 'paw': [x, 48]}
            draw.rectangle((x-3, 18, x+3, 50), fill='#c29a65')
            draw.rectangle((x-3, 18, x-2, 47), fill='#765533')
            draw.rectangle((x-2, 46, x+3, 50), fill='#fff0d3')
            # Asymmetric toe marks not present in the palette sample contract.
            draw.point((x-1, 49), fill='#413127')
            draw.point((x+2, 48), fill='#a46238')
        self.art = LimbArt(self.base, self.legs)

    def test_rest_pose_keeps_every_source_pixel(self):
        rendered = Image.new('RGBA', self.base.size)
        for name, leg in self.legs.items():
            x = leg['root'][0]
            rendered.alpha_composite(self.art.render(name, [leg['root'], [x, 34], leg['paw']]))
        np.testing.assert_array_equal(np.asarray(rendered), np.asarray(self.base))

    def test_bent_leg_keeps_rigid_toe_marks_without_recoloring(self):
        result = self.art.render('near', [[16, 20], [9, 31], [21, 42]])
        # The paw moved by (+5, -6), without a change to its pixel design.
        expected = self.base.crop((13, 46, 20, 51))
        np.testing.assert_array_equal(np.asarray(result.crop((18, 40, 25, 45))), np.asarray(expected))
        allowed = {tuple(p) for p in np.asarray(self.base).reshape(-1, 4) if p[3]}
        self.assertTrue(all(tuple(p) in allowed for p in np.asarray(result).reshape(-1, 4) if p[3]))
        # No repeated end row protruding beneath the paw.
        self.assertLessEqual(result.getbbox()[3], 45)

    def test_pose_resampling_keeps_keys_and_interpolates_loop_seam(self):
        keys = [{'paw': [0, 4], 'angle': 0}, {'paw': [8, 0], 'angle': 10}]
        self.assertEqual(sample_cycle(keys, 0), keys[0])
        self.assertEqual(sample_cycle(keys, .5), keys[1])
        self.assertEqual(sample_cycle(keys, .25), {'paw': [4, 2], 'angle': 5})
        self.assertEqual(sample_cycle(keys, .75), {'paw': [4, 2], 'angle': 5})
        self.assertEqual(sample_cycle(keys, 1), keys[0])


if __name__ == '__main__':
    unittest.main()
