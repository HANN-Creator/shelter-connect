import sys
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src/main/resources/motion-harness'))
from outline import restore_outline, source_edges


class OutlineTest(unittest.TestCase):
    LINE = (60, 45, 30, 255)
    COAT = (249, 238, 218, 255)

    def test_cut_edge_restored_without_new_seams_or_silhouette_changes(self):
        image = Image.new('RGBA', (9, 9), (12, 34, 56, 0))
        for y in range(2, 7):
            for x in range(1, 8):
                image.putpixel((x, y), self.COAT)
        image.putpixel((4, 4), (220, 196, 160, 255))  # Inner shoulder shade.
        image.putpixel((1, 3), self.LINE)  # An existing section of the border.
        before = np.array(image)
        result = restore_outline(image, self.LINE)
        after = np.array(result)
        expected = before.copy()
        expected[2, 1:8, :3] = self.LINE[:3]
        expected[6, 1:8, :3] = self.LINE[:3]
        expected[2:7, 1, :3] = self.LINE[:3]
        expected[2:7, 7, :3] = self.LINE[:3]
        np.testing.assert_array_equal(after, expected)
        np.testing.assert_array_equal(after[:, :, 3], before[:, :, 3])
        np.testing.assert_array_equal(restore_outline(result, self.LINE), after)

    def test_enclosed_hole_does_not_get_an_internal_dark_ring(self):
        image = Image.new('RGBA', (9, 9), self.COAT)
        image.putpixel((4, 4), (0, 0, 0, 0))
        result = restore_outline(image, self.LINE)
        for point in ((4, 3), (4, 5), (3, 4), (5, 4)):
            self.assertEqual(result.getpixel(point), self.COAT)
        self.assertEqual(result.getpixel((4, 4)), image.getpixel((4, 4)))
        self.assertEqual(result.getpixel((0, 4)), self.LINE)

    def test_open_notch_is_outlined_but_alpha_is_never_filled(self):
        image = Image.new('RGBA', (7, 7), self.COAT)
        for y in range(4):
            image.putpixel((3, y), (0, 0, 0, 0))
        image.putpixel((2, 2), (*self.COAT[:3], 128))
        result = restore_outline(image, self.LINE)
        self.assertEqual(result.getpixel((2, 2)), (*self.LINE[:3], 128))
        self.assertEqual(result.getpixel((3, 4)), self.LINE)
        np.testing.assert_array_equal(np.array(result)[:, :, 3], np.array(image)[:, :, 3])

    def test_original_outline_shades_survive_but_new_cut_edges_are_repaired(self):
        original=Image.new('RGBA',(12,12))
        original.paste(self.COAT,(2,2,10,10))
        light_line=(130,110,90,255)
        original.putpixel((2,5),light_line)
        original.putpixel((9,5),self.LINE)
        provenance=source_edges(original)
        cut=original.copy()
        for x in range(6,10):
            for y in range(2,10):cut.putpixel((x,y),(0,0,0,0))
        result=restore_outline(cut,self.LINE,np.asarray(provenance)[:,:,0]>127)
        # Existing fur/line edge colors are exact, not forced to one dark color.
        self.assertEqual(result.getpixel((2,5)),light_line)
        self.assertEqual(result.getpixel((3,2)),self.COAT)
        self.assertEqual(result.getpixel((5,5)),self.LINE)
        np.testing.assert_array_equal(np.asarray(result)[:,:,3],np.asarray(cut)[:,:,3])

    def test_empty_and_single_pixel_frames(self):
        image = Image.new('RGBA', (1, 1))
        self.assertEqual(restore_outline(image, self.LINE).tobytes(), image.tobytes())
        image.putpixel((0, 0), self.COAT)
        self.assertEqual(restore_outline(image, self.LINE).getpixel((0, 0)), self.LINE)


if __name__ == '__main__':
    unittest.main()
