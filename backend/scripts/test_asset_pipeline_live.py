"""Offline checks for the live verifier; never connect or incur generation costs."""
import copy
import io
import unittest
from urllib.request import Request

from check_asset_pipeline_live import CheckFailed, NoRedirect, checked_url, check_manifest, check_sheet, API, PROJECT


def manifest():
    return {
        'availableActions': ['IDLE', 'WALK', 'RUN', 'SIT'],
        'frameSize': {'width': 64, 'height': 64}, 'anchorPixels': {'x': 32, 'y': 60},
        'fallbackAction': 'IDLE',
        'animations': {action: {'frameCount': count, 'loop': action != 'SIT',
            'holdLastFrame': action == 'SIT', 'frames': [
                {'x': i * 64, 'y': 0, 'width': 64, 'height': 64, 'durationMs': duration}
                for i in range(count)]}
            for action, count, duration in [('IDLE', 16, 140), ('WALK', 24, 60), ('RUN', 24, 30), ('SIT', 16, 90)]}
    }


class LiveAssetVerifierTest(unittest.TestCase):
    def test_rejects_credentials_other_hosts_ports_http_and_deceptive_suffixes(self):
        self.assertEqual(checked_url(API + '/v1/me'), API + '/v1/me')
        self.assertEqual(checked_url(PROJECT + '/auth/v1/token'), PROJECT + '/auth/v1/token')
        for value in ['http://shelter-connect-dev.onrender.com', API + '.evil.test',
                      'https://user:secret@shelter-connect-dev.onrender.com', API + ':443/',
                      'https://evil.test', API + '/#secret']:
            with self.subTest(value=value), self.assertRaises(CheckFailed):
                checked_url(value)

    def test_redirects_never_forward_a_token(self):
        self.assertIsNone(NoRedirect().redirect_request(Request(API), None, 302, '', {}, 'https://evil.test'))

    def test_detects_missing_action_bad_frame_bounds_and_wrong_playback_rules(self):
        valid = manifest()
        actions = set(valid['availableActions'])
        check_manifest(valid, actions)
        for mutate in [
            lambda m: m['animations'].pop('RUN'),
            lambda m: m['animations']['WALK']['frames'][-1].update(x=0),
            lambda m: m['animations']['RUN']['frames'][0].update(durationMs=60),
            lambda m: m['animations']['SIT'].update(loop=True),
            lambda m: m.update(fallbackAction='RUN'),
        ]:
            broken = copy.deepcopy(valid)
            mutate(broken)
            with self.assertRaises(CheckFailed):
                check_manifest(broken, actions)

    def test_sheet_rejects_empty_static_opaque_or_incorrectly_sized_output(self):
        from PIL import Image
        def png(image):
            stream = io.BytesIO()
            image.save(stream, format='PNG')
            return stream.getvalue()
        valid = Image.new('RGBA', (128, 64))
        valid.putpixel((10, 10), (150, 100, 50, 255))
        valid.putpixel((75, 10), (150, 100, 50, 255))
        self.assertEqual(check_sheet(png(valid), 2), 2)
        still = valid.copy()
        still.putpixel((75, 10), (0, 0, 0, 0))
        still.putpixel((74, 10), (150, 100, 50, 255))
        for broken in [still, Image.new('RGBA', (128, 64)),
                       Image.new('RGBA', (128, 64), (100, 100, 100, 255)),
                       Image.new('RGBA', (64, 64))]:
            with self.assertRaises(CheckFailed):
                check_sheet(png(broken), 2)


if __name__ == '__main__':
    unittest.main()
