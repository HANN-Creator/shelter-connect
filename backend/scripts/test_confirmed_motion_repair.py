"""Actual saved frame provenance and shared first-generation/repair prevention, not new visual QA."""
import json
import unittest
from pathlib import Path
from PIL import Image
from styled_dog.client import digest
from styled_dog.quality import load_quality, quality_binding
from styled_dog.pipeline import load_rules, motion_payload


class ConfirmedMotionRepairTest(unittest.TestCase):
    def test_saved_nine_frames_and_both_conflicting_observations_remain_intact(self):
        root = Path(__file__).parent / 'fixtures/confirmed-motion-v37'
        evidence = json.loads((root / 'evidence.json').read_text())
        for name, expected in evidence['hashes'].items():
            self.assertEqual(digest(root / name), expected)
        with Image.open(root / 'idle-north.png') as sheet:
            self.assertEqual(sheet.size, (360, 40))
        report = json.loads((root / 'review.json').read_text())
        self.assertFalse(report['passed'])
        self.assertEqual(report['motionDecision'], 'UNCERTAIN')
        for key in (None, 'rawEditReview', 'restoredReview'):
            r = report if key is None else report[key]
            observers = [{p['property']: p for p in r[k]['properties']} for k in ('initialVision', 'consistencyReview')]
            self.assertEqual([o['idleStillness']['state'] for o in observers], ['PASS', 'FAIL'])
            self.assertEqual([o['tail']['state'] for o in observers], ['FAIL', 'FAIL'])
            self.assertEqual(set(observers[0]['tail']['frames']) & set(observers[1]['tail']['frames']), set(range(1, 8)))

    def test_all_initial_and_repair_directions_share_fixed_idle_markings(self):
        quality = load_quality()
        self.assertIn('coat markings', quality['actions']['IDLE'])
        for direction in ('south', 'north', 'west', 'east'):
            for attempt in (0, 1, 2):
                policy = {'attempt': attempt, 'issues': [], 'contract': {'tailCarriage': 'UNKNOWN'},
                          'rulesSha256': quality_binding()['sha256'], 'recoveryVersion': quality['recovery']['version']}
                payload = motion_payload({'seed': 1, 'motionDescription': 'a puppy', 'rearDescription': 'puppy rear'},
                                         load_rules(), 'IDLE', direction, {'type': 'base64', 'base64': 'fixture'}, policy)
                self.assertIn('coat markings', payload['description'])
                self.assertLessEqual(len(payload['description']), 1000)
