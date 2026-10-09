"""Lossless replay of the actual10-dog trial; recorded visual labels are not a new live review."""
import unittest
from pathlib import Path
from PIL import Image
from styled_dog.client import read,digest
from styled_dog.quality import frame_audit,load_quality,quality_binding,frontal_head_growth
from styled_dog.pipeline import motion_payload,load_rules
class RecoveryTest(unittest.TestCase):
 def test_actual_rejected_and_selected_frames_are_preserved_and_padded_without_resampling(self):
  root=Path(__file__).parent/'fixtures/pilot10-recovery-v15';e=read(root/'evidence.json')
  for p,expected in e['sha256'].items():self.assertEqual(digest(root/p),expected)
  self.assertFalse(e['cases']['base-1']['passed']);self.assertFalse(e['cases']['base-2']['structuralPassed']);self.assertTrue(e['cases']['base-3']['passed'])
  for d in ('south','north','west','east'):
   base=Image.open(root/'base-3'/f'{d}.png').convert('RGBA');seed=Image.open(root/'seeds'/f'{d}.png').convert('RGBA')
   self.assertEqual(seed.size,(40,40));self.assertEqual(seed.crop((4,4,36,36)).tobytes(),base.tobytes())
   im=Image.open(root/'selected'/f'walk-{d}.png').convert('RGBA');self.assertEqual(im.size,(360,40))
   fs=[im.crop((i*40,0,i*40+40,40)) for i in range(9)]
   self.assertTrue(frame_audit(fs,seed,'WALK',d,'UNKNOWN')['structuralPassed'])
   fs[8].putpixel((39,20),(1,2,3,255));self.assertIn('CANVAS_CLIPPING',frame_audit(fs,seed,'WALK',d,'UNKNOWN')['issues'])
 def test_all_initial_directions_and_actions_get_front_occlusion_regardless_of_tail(self):
  for action in load_rules()['actions']:
   for direction in load_rules()['directions']:
    for tail in ('LOW','LEVEL','HIGH','CURLED','UNKNOWN'):
     p=motion_payload({'seed':1,'motionDescription':'a puppy','rearDescription':'puppy rear'},load_rules(),action,direction,{'type':'base64','base64':'fixture'},
       {'attempt':0,'issues':[],'contract':{'tailCarriage':tail},'rulesSha256':quality_binding()['sha256'],'recoveryVersion':load_quality()['recovery']['version']})
     self.assertIn('crown',p['description']);self.assertIn('40x40',p['description']);self.assertLessEqual(len(p['description']),1000)

 def test_actual_crown_is_blocked_and_ten_selected_fronts_stay_valid(self):
  root=Path(__file__).parent/'fixtures/pilot10-recovery-v15';cases=root/'front-walk-regressions'
  for p,h in read(cases/'sha256.json').items():self.assertEqual(digest(cases/p),h)
  dogs=[p for p in cases.iterdir() if p.is_dir()];self.assertEqual(len(dogs),10)
  for d in dogs:
   seed=Image.open(d/'seed.png').convert('RGBA');sheet=Image.open(d/'walk.png').convert('RGBA')
   self.assertEqual(frontal_head_growth([sheet.crop((i*40,0,i*40+40,40)) for i in range(9)],seed),[],d.name)
  seed=Image.open(root/'seeds/south.png').convert('RGBA')
  frames=[Image.open(root/'initial-walk-south'/f'{i:02}.png').convert('RGBA') for i in range(9)]
  self.assertEqual(frontal_head_growth(frames,seed),[4,5,6])
  self.assertIn('TAIL_CARRIAGE',frame_audit(frames,seed,'WALK','south','UNKNOWN')['issues'])
