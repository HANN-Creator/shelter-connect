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
from styled_dog.quality import load_quality, quality_binding, frame_audit, audit_run, idle_motion_frames, pixel_evidence, TAILS


def dog_frame():
    frame=Image.new('RGBA',(32,32))
    draw=ImageDraw.Draw(frame)
    draw.rectangle((9,4,21,14),fill='#8a5737')
    draw.rectangle((7,14,24,24),fill='#bf8c51')
    draw.rectangle((9,23,12,29),fill='#8a5737')
    draw.rectangle((20,23,23,29),fill='#8a5737')
    return frame


def decode_recorded_alpha(rows):
    frame=Image.new('RGBA',(32,32))
    for y,row in enumerate(rows):
        for x in range(32):
            if int(row,16)&(1<<(31-x)):frame.putpixel((x,y),(70,70,70,255))
    return frame


class QualityRegressionTest(unittest.TestCase):
    def test_deployed_tail_coordinate_failure_is_preserved_without_snapping(self):
        root=Path(__file__).parent/'fixtures/tail-coordinate-focus-v20'
        evidence=read(root/'evidence.json')
        for file,sha in evidence['sha256'].items():self.assertEqual(digest(root/file),sha)
        self.assertFalse(evidence['published']);self.assertFalse(evidence['imageBytesChangedForLocalization'])
        review=read(root/'deployed-review.json')
        self.assertFalse(review['passed']);self.assertTrue(review['generalPropertyReview']['tailConsistent'])
        self.assertEqual(review['tailEvidence']['uncertainDirections'],['east'])
        im=Image.open(root/'east.png').convert('RGBA')
        v=next(v for v in review['tailEvidence']['observation']['views'] if v['direction']=='east')
        invalid=[(p['x'],p['y']) for p in v['tailPixelPath'] if not im.getpixel((p['x'],p['y']))[3]]
        self.assertEqual(invalid,[(2,11),(2,10),(2,9)])
        self.assertTrue(any(c.get('fixture')=='tail-coordinate-focus-v20/evidence.json' for c in load_quality()['regressions']))

    def test_deployed_repaired_seed_recheck_keeps_exact_original_evidence(self):
        root=Path(__file__).parent/'fixtures/repaired-seed-recheck-v19'
        evidence=read(root/'evidence.json')
        self.assertEqual(evidence['failureCode'],'RECOVERY_INPUT_CHANGED')
        self.assertEqual(evidence['repairCount'],1)
        self.assertEqual(evidence['lastArchiveRepairCount'],evidence['repairCount'])
        self.assertTrue(evidence['lastArchiveIsRecheck'])
        self.assertTrue(evidence['archivedResultEqualsCurrent'])
        self.assertEqual(evidence['pixelLabCallsDuringRecheck'],0)
        self.assertFalse(evidence['visionCalledBeforeFailure'])
        self.assertFalse(evidence['published'])
        for direction,sha in evidence['seedHashes'].items():
            self.assertEqual(digest(root/evidence['seedFixture']/f'{direction}.png'),sha)
        cases=load_quality()['regressions']
        self.assertTrue(any(c['fixture']=='repaired-seed-recheck-v19/evidence.json' for c in cases if c.get('fixture')))

    def test_actual_small_eye_edit_preserves_body_alpha_and_all_direction_hashes(self):
        from styled_dog.quality import seed_margin_audit
        root=Path(__file__).parent/'fixtures/small-seed-eyes-v14'
        evidence=read(root/'evidence.json')
        for file,sha in evidence['sha256'].items(): self.assertEqual(digest(root/file),sha)
        self.assertFalse(evidence['productionPublished'])
        self.assertTrue(evidence['automaticLocatorUsed'])
        self.assertFalse(evidence['operatorCoordinatesUsedInAutomatic'])
        self.assertEqual(evidence['repairTrigger'],'USER_REQUESTED_EYE_EDIT')
        self.assertTrue(read(root/'automatic/before-review.json')['passed'])
        self.assertFalse(read(root/'source/historical-review.json')['passed'])
        plan=read(root/'automatic/plan.json');changed=0;after={}
        for direction in ('south','north','west','east'):
            old=Image.open(root/'source'/f'{direction}.png').convert('RGBA')
            new=Image.open(root/'automatic'/f'{direction}.png').convert('RGBA');after[direction]=new
            self.assertEqual(new.size,(32,32))
            self.assertEqual(old.getchannel('A').tobytes(),new.getchannel('A').tobytes())
            for y in range(32):
                for x in range(32):
                    if old.getpixel((x,y))==new.getpixel((x,y)): continue
                    changed+=1
                    self.assertTrue(any(b['direction']==direction and b['x']<=x<b['x']+b['width']
                                        and b['y']<=y<b['y']+b['height'] for b in plan['regions']))
        self.assertEqual(changed,64)
        self.assertFalse(seed_margin_audit(after)['issues'])
        self.assertEqual(digest(root/'source/north.png'),digest(root/'automatic/north.png'))

    def test_unapproved_seed_alignment_only_translates_complete_native_pixels(self):
        from styled_dog.quality import align_seed, seed_margin_audit
        seed=Image.new('RGBA',(32,32))
        for y in range(3,31):
            for x in range(6,26): seed.putpixel((x,y),(x*7,y*6,33,255))
        before=seed.tobytes();aligned,dx,dy=align_seed(seed)
        self.assertEqual((dx,dy),(0,-1));self.assertEqual(seed.tobytes(),before)
        self.assertEqual(sorted(p for p in seed.getdata() if p[3]),sorted(p for p in aligned.getdata() if p[3]))
        self.assertFalse(seed_margin_audit({d:aligned for d in ('south','north','west','east')})['issues'])
        original=Image.open(Path(__file__).parent/'fixtures/oshu-motion-learning-v13/directions/west.png').convert('RGBA')
        unchanged,dx,dy=align_seed(original);self.assertEqual((dx,dy),(0,0));self.assertEqual(unchanged.tobytes(),original.tobytes())

    def test_actual_oshu_margin_and_clipping_preserve_original_hashes_and_nine_frames(self):
        from styled_dog.quality import seed_margin_audit
        from styled_dog.tail_repair import read_sheet
        root=Path(__file__).parent/'fixtures/oshu-motion-learning-v13'
        evidence=read(root/'evidence.json')
        for name,sha in evidence['sha256'].items(): self.assertEqual(digest(root/name),sha)
        seeds={d:Image.open(root/'directions'/f'{d}.png').convert('RGBA') for d in ('south','north','west','east')}
        result=seed_margin_audit(seeds)
        self.assertEqual(result['marginDirections'],['south','north','west','east'])
        self.assertEqual(result['clearPixelsLeftTopRightBottom']['west'],[1,2,1,1])
        for action,direction in product(('WALK','SIT'),('west','east')):
            frames=read_sheet(root/'sheets'/f'{action.lower()}-{direction}.png')
            self.assertEqual(len(frames),9)
            self.assertIn('CANVAS_CLIPPING',frame_audit(frames,seeds[direction],action,direction,'LOW')['issues'])
        for direction in ('north','west','east'):
            pixels=pixel_evidence(read_sheet(root/'sheets'/f'idle-{direction}.png'))
            self.assertTrue(pixels['alphaStable'])
            self.assertLessEqual(pixels['maxVisibleRgbDelta'],64)
        # The fixture preserves historical IDLE failures: these measurements alone never pass them.
        self.assertEqual(evidence['observedFailed']['idle-east'],['IDLE_MOTION'])

    def test_actual_learned_edits_and_regeneration_keep_every_failed_frame(self):
        from styled_dog.tail_repair import read_sheet
        root=Path(__file__).parent/'fixtures/oshu-motion-learning-v13'
        evidence=read(root/'actual-followup.json')
        self.assertFalse(evidence['deployed'])
        self.assertFalse(evidence['productionDataChanged'])
        self.assertEqual(evidence['recheckPassed'],8)
        self.assertFalse(evidence['newSeedPassed'])
        for strategy in ('edits','regenerated'):
            cases=evidence['phases'][strategy]
            self.assertEqual(len(cases),4)
            for case in cases:
                path=root/case['file'];frames=read_sheet(path)
                self.assertEqual(digest(path),case['sha256'])
                self.assertEqual(len(frames),9)
                self.assertEqual(case['state'],'EXHAUSTED')
                self.assertFalse(case['productionPublished'])
                self.assertFalse(case['rawReport']['passed'])
                self.assertEqual(pixel_evidence(frames),case['rawReport']['pixelEvidence'])
                action,direction=case['label'].split('-')
                audit=frame_audit(frames,frames[0],action.upper(),direction,'UNKNOWN')
                self.assertIn('CANVAS_CLIPPING',audit['issues'])
                self.assertEqual(audit['edgeFrames'],case['rawReport']['edgeFrames'])

    def test_live_paired_review_keeps_idle_and_sit_failures_without_overriding_vision(self):
        from styled_dog.tail_repair import read_sheet
        root=Path(__file__).parent/'fixtures'
        fixture=read(root/'paired-review-live.json')
        self.assertFalse(fixture['sourcePixelsChanged'])
        self.assertFalse(fixture['followUpModelReviewPerformed'])
        self.assertEqual(fixture['newPixelLabRequests'],0)
        self.assertEqual(len(fixture['cases']),12)
        failed=[]
        for case in fixture['cases']:
            if not case['variants']['restored']['actualReport']['passed']:failed.append(case['label'])
            for record in case['variants'].values():
                path=root/record['file'];frames=read_sheet(path);report=record['actualReport']
                self.assertEqual(digest(path),record['sha256'])
                self.assertEqual(len(frames),9)
                self.assertEqual(pixel_evidence(frames),report['pixelEvidence'])
                self.assertEqual(report['rulesSha256'],fixture['rulesSha256'])
                self.assertEqual(report['reviewLayout'],'matched-direction-frame-pairs-v1')
                self.assertTrue(frame_audit(frames,frames[0],case['action'],case['direction'],'UNKNOWN')['structuralPassed'])
        self.assertEqual(set(failed),{'idle-west','idle-east','sit-south','sit-north','sit-west'})
        self.assertEqual(fixture['passedClips'],12-len(failed))
        presentation=load_quality()['reviewPresentation']
        self.assertEqual(presentation['actions'],['IDLE'])
        self.assertEqual(presentation['otherActions'],'four-direction-temporal-grid-v1')

    def test_live_recheck_preserves_failed_verdicts_and_all_original_pixels(self):
        from styled_dog.tail_repair import read_sheet
        root=Path(__file__).parent/'fixtures'
        fixture=read(root/'idle-review-recheck.json')
        self.assertFalse(fixture['newPresentationLiveVerified'])
        self.assertFalse(fixture['sourcePixelsChanged'])
        self.assertEqual(fixture['newPixelLabRequests'],0)
        self.assertEqual(len(fixture['cases']),4)
        for case in fixture['cases']:
            self.assertFalse(case['actualRecheckReport']['passed'])
            self.assertIn('IDLE_MOTION',case['actualRecheckReport']['issues'])
            for record in case['variants'].values():
                path=root/record['file'];frames=read_sheet(path)
                before=[f.tobytes() for f in frames]
                self.assertEqual(digest(path),record['sha256'])
                self.assertEqual(len(frames),9)
                self.assertEqual(pixel_evidence(frames),record['pixelEvidence'])
                self.assertTrue(frame_audit(frames,frames[0],'IDLE',case['direction'],'UNKNOWN')['structuralPassed'])
                self.assertEqual(before,[f.tobytes() for f in frames])
        self.assertEqual(load_quality()['reviewPresentation']['version'],'matched-direction-frame-pairs-v1')

    def test_actual_idle_shading_has_fixed_alpha_without_overriding_visual_failure(self):
        from styled_dog.tail_repair import read_sheet
        root=Path(__file__).parent/'fixtures'
        fixture=read(root/'idle-review-evidence.json')
        self.assertFalse(fixture['originalQualityReport']['passed'])
        self.assertFalse(fixture['newModelReviewPerformed'])
        for record in fixture['clips'].values():
            frames=read_sheet(root/record['file']);before=[f.tobytes() for f in frames]
            self.assertEqual(digest(root/record['file']),record['sha256'])
            self.assertEqual(pixel_evidence(frames),record['pixelEvidence'])
            self.assertTrue(pixel_evidence(frames)['subtleShadingOnly'])
            self.assertTrue(frame_audit(frames,frames[0],'IDLE','north','UNKNOWN')['visualReviewRequired'])
            self.assertEqual(before,[f.tobytes() for f in frames])

    def test_same_bounds_movement_and_high_contrast_details_are_not_subtle_shading(self):
        seed=dog_frame();frames=[seed.copy() for _ in range(9)]
        frames[8].putpixel((10,10),(255,255,255,255))
        evidence=pixel_evidence(frames)
        self.assertTrue(evidence['alphaStable']);self.assertFalse(evidence['subtleShadingOnly'])
        frames[8]=seed.copy();frames[8].putpixel((10,10),(0,0,0,0))
        evidence=pixel_evidence(frames)
        self.assertEqual(evidence['boundsExclusive'][0],evidence['boundsExclusive'][8])
        self.assertEqual(evidence['alphaChangedFromFrame0'][8],1)
        self.assertFalse(evidence['subtleShadingOnly'])
        empty=[Image.new('RGBA',(32,32)) for _ in range(9)]
        self.assertFalse(pixel_evidence(empty)['subtleShadingOnly'])

    def test_continuation_replays_actual_failures_and_all_nine_candidate_frames(self):
        from styled_dog.tail_repair import read_sheet, seed_idle_payload
        root=Path(__file__).parent/'fixtures'
        fixture=read(root/'idle-motion-alpha.json')['boundedContinuation']
        self.assertFalse(fixture['modelVisualRecheckPerformed'])
        for case in fixture['clips']:
            action,direction=case['label'].split('-')
            before=read_sheet(root/case['before']);candidate=read_sheet(root/case['candidate'])
            self.assertEqual(digest(root/case['before']),case['sourceSha256'])
            self.assertFalse(frame_audit(before,before[0],action.upper(),direction,'UNKNOWN')['structuralPassed'])
            self.assertTrue(frame_audit(candidate,before[0],action.upper(),direction,'UNKNOWN')['structuralPassed'])
            self.assertEqual(len(candidate),9)
            self.assertEqual(candidate[0].tobytes(),before[0].tobytes())
        seed=Image.open(root/'motion-continuation/north.png').convert('RGBA')
        payload=seed_idle_payload(seed,'north')
        self.assertEqual(len(payload['frames']),9)
        self.assertTrue(all(frame==payload['frames'][0] for frame in payload['frames']))
        self.assertLessEqual(len(payload['description']),2000)

    def test_live_edit_success_and_failures_replay_all_raw_and_restored_frames(self):
        cases={}
        for name in ('idle-motion-alpha.json','tail-repair-alpha.json','sit-tail-alpha.json'):
            fixture=read(Path(__file__).parent/'fixtures'/name)['liveEditComparisons']
            self.assertFalse(fixture['modelVisualRecheckPerformed'])
            for case in fixture['clips']:
                versions={}
                for version,record in case['variants'].items():
                    frames=[decode_recorded_alpha(rows) for rows in record['frames']]
                    self.assertEqual(len(frames),9)
                    before=[frame.tobytes() for frame in frames]
                    audit=frame_audit(frames,frames[0],case['action'],case['direction'],'UNKNOWN')
                    with self.subTest(clip=case['label'],version=version):
                        self.assertEqual(audit['edgeFrames'],record['expectedEdgeFrames'])
                        self.assertEqual(audit['idleMotionFrames'],record['expectedIdleMotionFrames'])
                        self.assertEqual(audit['structuralPassed'],record['expectedStructuralPassed'])
                        self.assertTrue(audit['visualReviewRequired'])
                        self.assertEqual(before,[frame.tobytes() for frame in frames])
                    versions[version]=(frames,audit)
                self.assertEqual(versions['candidate'][0][0].tobytes(),versions['before'][0][0].tobytes())
                cases[case['label']]=versions
        # Actual successful idle edit provides a positive control, without pretending
        # the alpha replay can judge eye expression, identity, or subtle tail carriage.
        idle=cases['idle-south']
        self.assertIn('IDLE_MOTION',idle['before'][1]['issues'])
        for version in ('raw-edit','candidate'):
            self.assertTrue(idle[version][1]['structuralPassed'])
            self.assertEqual(idle_motion_frames(idle[version][0],idle['before'][0][0],'IDLE'),[])
        # A provider-completed edit remains rejected, including the true final frame.
        for version in ('raw-edit','candidate'):
            self.assertEqual(cases['walk-west'][version][1]['edgeFrames'],[2,3,4,5,6,7,8])
            self.assertEqual(cases['sit-west'][version][1]['edgeFrames'],[2,4,5,6])
            for label in ('walk-west','sit-west'):
                self.assertFalse(cases[label][version][1]['structuralPassed'])

    def test_scoped_learned_rules_extend_shared_payload_without_weakening_base_rules(self):
        from copy import deepcopy
        lesson={'id':'12345678-1234-1234-1234-123456789abc','sha256':'a'*64,
                'rulesSha256':quality_binding()['sha256'],'action':'SIT','direction':'west','tail':'UNKNOWN',
                'issue':'CANVAS_CLIPPING','prevention':'Keep the whole seated tail beside the hind paw throughout the descent.',
                'criterion':'The tail tip touches the canvas boundary while sitting or holding the pose.'}
        q={'contract':{'tailCarriage':'UNKNOWN'},'lessons':[deepcopy(lesson) for _ in range(5)]}
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

    def test_repeated_live_idle_failures_are_retained_and_sent_complete_to_edit(self):
        import base64
        import io
        from styled_dog.tail_repair import idle_edit_payload
        cases=read(Path(__file__).parent/'fixtures/idle-motion-alpha.json')['repeatedGenerations']['clips']
        self.assertEqual(len(cases),3)
        for case in cases:
            frames=[]
            for rows in case['frames']:
                frame=Image.new('RGBA',(32,32))
                for y,row in enumerate(rows):
                    for x in range(32):
                        if int(row,16)&(1<<(31-x)):frame.putpixel((x,y),(70,70,70,255))
                frames.append(frame)
            before=[f.tobytes() for f in frames]
            report=frame_audit(frames,frames[0],'IDLE','south','UNKNOWN')
            self.assertEqual(report['idleMotionFrames'],case['expectedIdleMotionFrames'])
            self.assertIn('IDLE_MOTION',report['issues'])
            for direction in ('south','north','west','east'):
                payload=idle_edit_payload(frames,direction)
                self.assertEqual(len(payload['frames']),9)
                self.assertLessEqual(len(payload['description']),2000)
                self.assertIn('Frame zero is the approved',payload['description'])
                self.assertIn('Remove the invented moving appendage',payload['description'])
                self.assertIn('Preserve any genuine visible tail',payload['description'])
                self.assertIn(direction+' facing',payload['description'])
                for original,item in zip(frames,payload['frames']):
                    decoded=Image.open(io.BytesIO(base64.b64decode(item['image']['base64']))).convert('RGBA')
                    self.assertEqual(original.tobytes(),decoded.tobytes())
            self.assertEqual(before,[f.tobytes() for f in frames])
            with self.assertRaises(ValueError):idle_edit_payload(frames[:8],'south')

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
