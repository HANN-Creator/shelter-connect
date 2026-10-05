"""Known-defect replay: offline pixels, provider limits, shared CLI/server inputs.

These tests do not pretend a mocked vision verdict measures live AI accuracy.
"""
from itertools import product
from pathlib import Path
import tempfile
import unittest
from PIL import Image, ImageDraw
from styled_dog.client import digest, read, write
from styled_dog.pipeline import load_rules, motion_payload, record_review
from styled_dog.quality import load_quality, quality_binding, frame_audit, audit_run, TAILS


def dog_frame():
    frame=Image.new('RGBA',(32,32))
    draw=ImageDraw.Draw(frame)
    draw.rectangle((9,4,21,14),fill='#8a5737')
    draw.rectangle((7,14,24,24),fill='#bf8c51')
    draw.rectangle((9,23,12,29),fill='#8a5737')
    draw.rectangle((20,23,23,29),fill='#8a5737')
    return frame


class QualityRegressionTest(unittest.TestCase):
    def test_front_low_wag_rejects_upper_appendage_without_vision_and_keeps_lower_wag(self):
        seed=dog_frame();frames=[seed.copy() for _ in range(9)]
        ImageDraw.Draw(frames[4]).rectangle((3,12,8,14),fill='#bf8c51')
        before=[f.tobytes() for f in frames]
        result=frame_audit(frames,seed,'TAIL_WAG','south','LOW')
        self.assertEqual(result['issues'],['TAIL_CARRIAGE'])
        self.assertEqual(result['silhouetteFrames'],[4])
        self.assertEqual(before,[f.tobytes() for f in frames])
        for action,direction,tail in [('SNIFF','south','LOW'),('TAIL_WAG','north','LOW'),
                                      ('TAIL_WAG','south','HIGH'),('TAIL_WAG','south','UNKNOWN')]:
            self.assertTrue(frame_audit(frames,seed,action,direction,tail)['structuralPassed'])
        normal=[seed.copy() for _ in range(9)]
        ImageDraw.Draw(normal[4]).rectangle((3,25,8,26),fill='#bf8c51')
        normal[5].putpixel((6,17),(1,2,3,255)) # tolerated one-pixel outline motion
        self.assertTrue(frame_audit(normal,seed,'TAIL_WAG','south','LOW')['structuralPassed'])

    def test_known_border_defects_are_rejected_but_one_pixel_inset_is_preserved(self):
        rules=load_quality();seed=dog_frame()
        cases=[c for c in rules['regressions'] if c['check']=='alpha']
        self.assertEqual({c['id'] for c in cases},{'left-tail-clipped','right-muzzle-clipped','rear-paw-clipped'})
        for case in cases:
            with self.subTest(case=case['id']):
                frames=[seed.copy() for _ in range(9)]
                frames[case['frame']].putpixel(tuple(case['pixel']),(31,42,53,1))
                before=[f.tobytes() for f in frames]
                result=frame_audit(frames,seed)
                self.assertFalse(result['structuralPassed'])
                self.assertEqual(result['edgeFrames'],[case['frame']])
                self.assertEqual(result['issues'],['CANVAS_CLIPPING'])
                self.assertEqual(before,[f.tobytes() for f in frames])
        for point in [(1,10),(30,10),(10,1),(10,30)]:
            frames=[seed.copy() for _ in range(9)];frames[8].putpixel(point,(1,2,3,128))
            self.assertTrue(frame_audit(frames,seed)['structuralPassed'])
        for point in [(0,10),(31,10),(10,0),(10,31)]:
            frames=[seed.copy() for _ in range(9)];frames[8].putpixel(point,(1,2,3,128))
            self.assertFalse(frame_audit(frames,seed)['structuralPassed'])

    def test_all_combinations_fit_provider_limits_and_keep_seed_loop_direction(self):
        rules=load_rules();issues=list(load_quality()['corrections'])
        traits={'seed':10,'motionDescription':'tan dog','rearDescription':'tan dog rear'}
        first={'base64':'offline-placeholder'}
        for action,direction,tail,attempt,failed in product(rules['actions'],rules['directions'],TAILS,[0,2],[[],issues]):
            with self.subTest(action=action,direction=direction,tail=tail,attempt=attempt,failed=failed):
                p=motion_payload(traits,rules,action,direction,first,{'contract':{'tailCarriage':tail},
                    'attempt':attempt,'issues':failed,'rulesSha256':quality_binding()['sha256']})
                self.assertLessEqual(len(p['description']),1000)
                self.assertLessEqual(len(p['initial_pose']),300)
                self.assertEqual(p['direction'],direction)
                self.assertEqual(p['first_frame'],first)
                self.assertEqual('last_frame' in p,rules['actions'][action]['loop'])
                if rules['actions'][action]['loop']:self.assertEqual(p['last_frame'],first)
                self.assertEqual(p['seed'],10+7919*attempt)
                self.assertFalse(p['enhance_prompt'])
                if failed:
                    for issue in failed:self.assertIn(load_quality()['corrections'][issue],p['description'])

    def test_invalid_findings_and_changed_rule_hash_fail_before_any_provider_request(self):
        rules=load_rules();traits={'seed':0,'motionDescription':'dog','rearDescription':'rear'}
        for changes in [{'issues':['RUN_SHELL']},{'issues':'TAIL_CARRIAGE'},{'attempt':3},
                        {'contract':{'tailCarriage':'INVENTED'}},{'rulesSha256':'changed'}]:
            with self.subTest(changes=changes),self.assertRaises(ValueError):
                motion_payload(traits,rules,'WALK','south',{},dict({'contract':{'tailCarriage':'LOW'}},**changes))

    def test_default_motion_still_contains_prevention_but_cli_review_requires_shared_tail(self):
        traits={'seed':0,'motionDescription':'dog','rearDescription':'rear'}
        p=motion_payload(traits,load_rules(),'SIT','north',{})
        self.assertIn('including the final hold',p['description'])
        self.assertIn('No visible eyes',p['description'])
        with self.assertRaisesRegex(ValueError,'shared tail carriage'):
            record_review(Path('/unused'),'A long enough review note without shared tail carriage')

    def test_raw_final_frame_is_audited_even_if_preview_tries_to_hold_frame_seven(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);seed=dog_frame();label='sit-north'
            (root/'directions').mkdir();seed.save(root/'directions/north.png')
            folder=root/'frames'/label;folder.mkdir(parents=True)
            for i in range(9):
                frame=seed.copy()
                if i==8:frame.putpixel((31,16),(1,2,3,255))
                frame.save(folder/f'{i:02}.png')
            write(root/'clips'/f'{label}.json',{'label':label,'action':'SIT','direction':'north','frameCount':9,
                'sourceSha256':digest(root/'directions/north.png'),'frameSha256':[digest(folder/f'{i:02}.png') for i in range(9)]})
            write(root/'frame-reviews.json',{'clips':{label:{'holdFromFrame':7,'reason':'Hide bad final frame'}}})
            report=audit_run(root)
            self.assertEqual(report['status'],'REQUIRES_REPAIR')
            self.assertEqual(report['clips'][label]['edgeFrames'],[8])
            self.assertTrue(report['visualReviewRequired'])
            self.assertEqual(read(root/'quality-audit.json'),report)


if __name__=='__main__':unittest.main()
