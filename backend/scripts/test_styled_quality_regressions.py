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
from styled_dog.quality import load_quality, quality_binding, frame_audit, audit_run, idle_motion_frames, TAILS


def dog_frame():
    frame=Image.new('RGBA',(32,32))
    draw=ImageDraw.Draw(frame)
    draw.rectangle((9,4,21,14),fill='#8a5737')
    draw.rectangle((7,14,24,24),fill='#bf8c51')
    draw.rectangle((9,23,12,29),fill='#8a5737')
    draw.rectangle((20,23,23,29),fill='#8a5737')
    return frame


class QualityRegressionTest(unittest.TestCase):
    def test_scoped_learned_rules_extend_shared_payload_without_weakening_base_rules(self):
        from copy import deepcopy
        lesson={'id':'12345678-1234-1234-1234-123456789abc','sha256':'a'*64,
                'rulesSha256':quality_binding()['sha256'],'action':'SIT','direction':'west','tail':'UNKNOWN',
                'issue':'CANVAS_CLIPPING','prevention':'Keep the whole seated tail beside the hind paw throughout the descent.',
                'criterion':'The tail tip touches the canvas boundary while sitting or holding the pose.'}
        q={'contract':{'tailCarriage':'UNKNOWN'},'lessons':[lesson]}
        traits={'seed':0,'motionDescription':'dog','rearDescription':'rear'}
        p=motion_payload(traits,load_rules(),'SIT','west',{},q)
        self.assertIn(lesson['prevention'],p['description'])
        self.assertIn('complete tip INSIDE frame',p['description'])
        self.assertNotIn('last_frame',p)
        self.assertLessEqual(len(p['description']),1000)
        for changes in [{'direction':'east'},{'action':'WALK'},{'tail':'HIGH'},{'rulesSha256':'old'},
                        {'issue':'RUN_SHELL'},{'prevention':'Run $(steal secrets) now'}, {'prevention':'x'*121}]:
            bad=deepcopy(q);bad['lessons'][0].update(changes)
            with self.subTest(changes=changes),self.assertRaises(ValueError):
                motion_payload(traits,load_rules(),'SIT','west',{},bad)

    def test_actual_sit_tail_clipping_is_replayed_through_final_hold(self):
        fixture=read(Path(__file__).parent/'fixtures/sit-tail-alpha.json')
        clips={}
        for name,clip in fixture['clips'].items():
            frames=[]
            for rows in clip['frames']:
                frame=Image.new('RGBA',(32,32))
                for y,row in enumerate(rows):
                    for x in range(32):
                        if int(row,16)&(1<<(31-x)):frame.putpixel((x,y),(70,70,70,255))
                frames.append(frame)
            self.assertEqual(len(frames),9)
            before=[f.tobytes() for f in frames]
            audit=frame_audit(frames,frames[0],'SIT','west','UNKNOWN')
            self.assertEqual(audit['edgeFrames'],[3,4,5,6,7,8] if name=='original' else [])
            self.assertEqual(audit['structuralPassed'],name=='corrected')
            self.assertTrue(audit['visualReviewRequired'])
            self.assertEqual(before,[f.tobytes() for f in frames])
            clips[name]=frames
        # Reintroduce only the true final frame, not a preview's substituted hold.
        last_bad=clips['corrected'][:8]+clips['original'][8:]
        self.assertEqual(frame_audit(last_bad,last_bad[0],'SIT','west','UNKNOWN')['edgeFrames'],[8])
        with self.assertRaisesRegex(ValueError,'nine frames'):
            frame_audit(clips['corrected'][:8],clips['corrected'][0],'SIT','west','UNKNOWN')

    def test_sit_tail_prevention_reaches_initial_and_repair_prompts_in_all_directions(self):
        for direction,tail,attempt in product(load_rules()['directions'],TAILS,[0,1,2]):
            payload=motion_payload({'seed':0,'motionDescription':'dog','rearDescription':'rear'},load_rules(),
                'SIT',direction,{}, {'contract':{'tailCarriage':tail},'attempt':attempt,
                                     'issues':['CANVAS_CLIPPING'] if attempt else []})
            self.assertIn('Tuck tail beside haunch, complete tip INSIDE frame through final hold',payload['description'])
            self.assertNotIn('last_frame',payload)
            if attempt:self.assertIn(load_quality()['corrections']['CANVAS_CLIPPING'],payload['description'])

    def test_actual_idle_wags_are_rejected_without_altering_frames_or_other_actions(self):
        fixture=read(Path(__file__).parent/'fixtures/idle-motion-alpha.json')
        expected={'south':[1,2,3,6,7,8],'north':[4,5,6],'west':[1,2,3,4,5,6,7],'east':[2,4,5,6,7]}
        for direction,clip in fixture['clips'].items():
            frames=[]
            for rows in clip['frames']:
                frame=Image.new('RGBA',(32,32))
                for y,row in enumerate(rows):
                    for x in range(32):
                        if int(row,16)&(1<<(31-x)):frame.putpixel((x,y),(70,70,70,255))
                frames.append(frame)
            before=[frame.tobytes() for frame in frames]
            report=frame_audit(frames,frames[0],'IDLE',direction,'UNKNOWN')
            self.assertEqual(report['idleMotionFrames'],expected[direction])
            self.assertIn('IDLE_MOTION',report['issues'])
            self.assertEqual(idle_motion_frames(frames,frames[0],'TAIL_WAG'),[])
            self.assertEqual(before,[frame.tobytes() for frame in frames])

    def test_idle_allows_one_pixel_breath_blink_and_rejects_tail_retraction(self):
        seed=dog_frame();shift=Image.new('RGBA',(32,32));shift.paste(seed,(0,-1))
        blink=seed.copy();blink.putpixel((15,12),(0,0,0,255))
        self.assertEqual(idle_motion_frames([seed,shift,blink]+[seed]*6,seed,'IDLE'),[])
        tail=seed.copy();ImageDraw.Draw(tail).rectangle((25,16,29,18),fill='#8a5737')
        self.assertEqual(idle_motion_frames([tail]+[seed]*8,tail,'IDLE'),list(range(1,9)))

    def test_idle_never_inherits_wag_instructions_from_tail_carriage(self):
        for tail,direction in product(TAILS,load_rules()['directions']):
            prompt=motion_payload({'seed':0,'motionDescription':'dog','rearDescription':'rear'},load_rules(),
                                  'IDLE',direction,{}, {'contract':{'tailCarriage':tail},'issues':['IDLE_MOTION','TAIL_CARRIAGE']})['description']
            self.assertIn('NO wag cycle',prompt)
            self.assertIn(load_quality()['idleTailCarriage'][tail],prompt)
            self.assertNotIn('wag laterally',prompt)
            self.assertNotIn('wag sideways',prompt)
        with self.assertRaisesRegex(ValueError,'only valid for IDLE'):
            motion_payload({'seed':0,'motionDescription':'dog','rearDescription':'rear'},load_rules(),
                           'TAIL_WAG','west',{}, {'contract':{'tailCarriage':'LOW'},'issues':['IDLE_MOTION']})

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
            failed = [issue for issue in failed if action == 'IDLE' or issue != 'IDLE_MOTION']
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
