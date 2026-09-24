"""Offline guard tests; never send requests or generate paid images."""
import copy
import unittest

from check_photo_motion_live import review_decision, local_manifest, CheckFailed
from test_asset_pipeline_live import manifest


class PhotoMotionReviewTest(unittest.TestCase):
    def test_rejects_stale_wrong_or_unreviewed_approval(self):
        binding = {'jobId': 'job-1', 'stage': 'rig', 'expectedRevision': 3, 'baseSha256': 'digest'}
        reviewed = dict(binding, decision='APPROVE', note='Inspected paws, mask and sampled colors')
        self.assertEqual(review_decision(reviewed, binding), reviewed)
        for change in ({'jobId': 'other'}, {'stage': 'animation'}, {'expectedRevision': 2},
                       {'baseSha256': 'different'}, {'decision': 'REJECT'},
                       {'decision': 'PENDING'}, {'note': ''}):
            with self.subTest(change=change), self.assertRaises(CheckFailed):
                review_decision(dict(reviewed, **change), binding)

    def test_each_animation_hash_must_match(self):
        binding = {'jobId': 'job', 'stage': 'animation', 'animationSha256': {'IDLE': 'a', 'WALK': 'b'}}
        reviewed = dict(binding, decision='APPROVE', note='Inspected every animation strip')
        changed = copy.deepcopy(reviewed)
        changed['animationSha256']['WALK'] = 'changed'
        with self.assertRaises(CheckFailed):
            review_decision(changed, binding)

    def test_preview_exports_only_playback_metadata_without_signed_credentials(self):
        source = manifest()
        source.update(behaviorRevision=2, baseUrl='https://private/?token=secret')
        for clip in source['animations'].values():
            clip.update(spritesheetUrl='https://private/?token=secret', internal='secret')
        preview = local_manifest(source)
        self.assertNotIn('secret', str(preview))
        self.assertNotIn('baseUrl', preview)
        self.assertEqual(preview['animations']['WALK']['spritesheetUrl'], 'walk.png')
        self.assertEqual(preview['animations']['WALK']['frames'], source['animations']['WALK']['frames'])


if __name__ == '__main__':
    unittest.main()
