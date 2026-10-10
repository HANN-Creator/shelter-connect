"""Source provenance and shared initial/repair SIT entry guidance; visual QA is separate."""
import json
import unittest
from pathlib import Path
from PIL import Image
from styled_dog.client import digest
from styled_dog.pipeline import motion_payload, load_rules
from styled_dog.quality import load_quality, quality_binding

class MotionRepairEvidenceTest(unittest.TestCase):
    def test_real_held_sources_keep_their_hashes_and_nine_frames(self):
        root=Path(__file__).parent/'fixtures/motion-repair-v38'
        evidence=json.loads((root/'evidence.json').read_text())
        for name,sha in evidence['hashes'].items():self.assertEqual(digest(root/name),sha)
        for label in evidence['cases']:
            with Image.open(root/(label+'.png')) as image:self.assertEqual(image.size,(360,40))
            report=json.loads((root/(label+'-qualityReport.json')).read_text())
            self.assertFalse(report['passed']);self.assertEqual(report['motionDecision'],'UNCERTAIN')
    def test_standing_entry_and_complete_tail_are_in_every_initial_and_repair_request(self):
        rules=load_quality();traits={'seed':1,'motionDescription':'puppy','rearDescription':'puppy rear'}
        for d in ('south','north','west','east'):
            for attempt in (0,1,2):
                q={'attempt':attempt,'contract':{'tailCarriage':'UNKNOWN'},'rulesSha256':quality_binding()['sha256'],'recoveryVersion':rules['recovery']['version']}
                first={'type':'base64','base64':'fixture'}
                p=motion_payload(traits,load_rules(),'SIT',d,first,q)
                self.assertIn('Start standing at frame0',p['description'])
                self.assertIn('then hold seated',p['description'])
                self.assertIn('Complete tail tucked INSIDE all frames',p['description'])
                self.assertEqual(p['first_frame'],first);self.assertNotIn('last_frame',p)
                self.assertLessEqual(len(p['description']),1000)
