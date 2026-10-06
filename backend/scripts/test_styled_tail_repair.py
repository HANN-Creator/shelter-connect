"""Lossless replay of real tail clipping; synthetic tests cover body preservation."""
from pathlib import Path
import base64
import io
import unittest
from PIL import Image, ImageDraw
from styled_dog.client import read
from styled_dog.tail_repair import (audit_edit, compose_tail, edit_payload, pixel_hash,
                                    read_sheet, sheet_hash, sheet_for)


class TailRepairTest(unittest.TestCase):
    def setUp(self):
        self.seed=Image.new('RGBA',(32,32))
        ImageDraw.Draw(self.seed).rectangle((10,5,22,28),fill=(170,100,50,255))
        self.seed.putpixel((20,6),(30,20,10,255))
        self.frames=[self.seed.copy() for _ in range(9)]
        for i,f in enumerate(self.frames):
            ImageDraw.Draw(f).rectangle((23,22,24+i%2,24),fill=(155,90,60,255))
            f.putpixel((15,10),(150,40,220,255)) # unwanted provider body recolor
        self.mask=Image.new('L',(32,32));ImageDraw.Draw(self.mask).rectangle((23,20,27,27),fill=255)
        self.review=self.binding()

    def binding(self,seed=None,source=None,frames=None,mask=None,direction='west',source_direction='west'):
        seed=seed or self.seed;source=source or self.seed;frames=frames or self.frames;mask=mask or self.mask
        return {'approved':True,'note':'Reviewed a complete low tail with the existing body preserved.',
                'tailCarriage':'LOW','direction':direction,'sourceDirection':source_direction,
                'seedPixelSha256':pixel_hash(seed),'sourceSeedPixelSha256':pixel_hash(source),
                'editPixelSha256':sheet_hash(frames),'maskPixelSha256':pixel_hash(mask)}

    def test_restores_exact_body_palette_and_first_frame_without_resizing(self):
        frames,report=compose_tail(self.seed,self.seed,self.frames,self.mask,self.review)
        self.assertEqual(frames[0].tobytes(),self.seed.tobytes())
        self.assertEqual(len(frames),9)
        for f in frames:
            self.assertEqual(f.size,(32,32))
            for y in range(32):
                for x in range(32):
                    if not self.mask.getpixel((x,y)):self.assertEqual(f.getpixel((x,y)),self.seed.getpixel((x,y)))
            self.assertTrue({f.getpixel((x,y)) for y in range(32) for x in range(32) if f.getpixel((x,y))[3]} <= {self.seed.getpixel((x,y)) for y in range(32) for x in range(32)})
        self.assertTrue(report['visualReviewRequired'])
        self.assertFalse(report['published'])
        self.assertGreater(report['tailSilhouetteCount'],1)

    def test_cannot_hide_bad_raw_frame_zero_or_final_frame_with_mask_or_seed_lock(self):
        for i in (0,8):
            bad=[f.copy() for f in self.frames];bad[i].putpixel((31,12),(1,2,3,255))
            with self.subTest(frame=i),self.assertRaisesRegex(ValueError,'Raw edit rejected'):
                compose_tail(self.seed,self.seed,bad,self.mask,self.binding(frames=bad))

    def test_detached_pixel_is_rejected_even_away_from_edge_or_outside_mask(self):
        bad=[f.copy() for f in self.frames];bad[4].putpixel((2,12),(1,2,3,255))
        self.assertEqual(audit_edit(bad,self.seed,'west')['detachedFrames'],[4])
        with self.assertRaisesRegex(ValueError,'DETACHED_PIXELS'):
            compose_tail(self.seed,self.seed,bad,self.mask,self.binding(frames=bad))

    def test_review_does_not_transfer_to_another_seed_edit_or_mask(self):
        for key in ('seedPixelSha256','sourceSeedPixelSha256','editPixelSha256','maskPixelSha256'):
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'Review must bind'):
                compose_tail(self.seed,self.seed,self.frames,self.mask,dict(self.review,**{key:'different'}))
        for change in ({'approved':False},{'tailCarriage':'HIGH'},{'note':'ok'}):
            with self.assertRaises(ValueError):compose_tail(self.seed,self.seed,self.frames,self.mask,dict(self.review,**change))

    def test_only_validated_opposite_side_tail_is_mirrored_onto_target_body(self):
        from PIL import ImageOps
        east=ImageOps.mirror(self.seed);east.putpixel((16,10),(1,2,3,255))
        mask=ImageOps.mirror(self.mask)
        frames,report=compose_tail(east,self.seed,self.frames,mask,
            self.binding(seed=east,mask=mask,direction='east'))
        self.assertTrue(report['mirrored'])
        self.assertEqual(frames[2].getpixel((16,10)),(1,2,3,255))
        with self.assertRaisesRegex(ValueError,'Only opposite'):
            compose_tail(east,self.seed,self.frames,mask,self.binding(seed=east,mask=mask,direction='north'))

    def test_empty_motion_or_color_flicker_alone_does_not_pass(self):
        frames=[self.seed.copy() for _ in range(9)]
        for i,f in enumerate(frames):f.putpixel((15,10),(i,30,40,255))
        with self.assertRaisesRegex(ValueError,'No visible tail'):
            compose_tail(self.seed,self.seed,frames,self.mask,self.binding(frames=frames))

    def test_payload_contains_all_nine_actual_frames_and_fixed_inward_edit_prompt(self):
        for direction in ('west','east','south','north'):
            payload=edit_payload(self.frames,direction)
            self.assertEqual(len(payload['frames']),9)
            self.assertLessEqual(len(payload['description']),2000)
            self.assertTrue(payload['no_background'])
            self.assertIn('COMPLETE',payload['description'])
            for original,item in zip(self.frames,payload['frames']):
                image=Image.open(io.BytesIO(base64.b64decode(item['image']['base64']))).convert('RGBA')
                self.assertEqual(original.tobytes(),image.tobytes())
        for frames in (self.frames[:8],[Image.new('RGBA',(64,64))]*9):
            with self.assertRaises(ValueError):edit_payload(frames,'west')

    def test_actual_recorded_defects_and_final_36_frames(self):
        fixture=read(Path(__file__).parent/'fixtures/tail-repair-alpha.json')
        def decode(rows):
            image=Image.new('RGBA',(32,32))
            for y,row in enumerate(rows):
                for x in range(32):
                    if int(row,16) & (1<<(31-x)):image.putpixel((x,y),(10,20,30,255))
            return image
        for d in ('west','east','south','north'):
            seed=decode(fixture['seeds'][d])
            final=[decode(r) for r in fixture['strips']['final-'+d]]
            self.assertEqual(final[0].tobytes(),seed.tobytes())
            self.assertTrue(audit_edit(final,seed,d)['structuralPassed'],d)
        for d,expected in [('west',[2,3,4,5,6,7]),('east',[1,2,3,5,6,7])]:
            bad=[decode(r) for r in fixture['strips']['before-'+d]]
            self.assertEqual(audit_edit(bad,decode(fixture['seeds'][d]),d)['edgeFrames'],expected)
        east=[decode(r) for r in fixture['strips']['edit-east']]
        rejected=audit_edit(east,decode(fixture['seeds']['east']),'east')
        self.assertEqual(rejected['edgeFrames'],list(range(9)))
        self.assertEqual(rejected['detachedFrames'],[1,5,8])
        front=[decode(r) for r in fixture['strips']['before-south']]
        self.assertEqual(audit_edit(front,decode(fixture['seeds']['south']),'south')['silhouetteFrames'],[2,3,4,6,7])

    def test_repeated_actual_walk_clipping_remains_visible_in_complete_edit_inputs(self):
        from styled_dog.tail_repair import margin_edit_payload
        from styled_dog.quality import frame_audit, load_quality
        fixture=read(Path(__file__).parent/'fixtures/tail-repair-alpha.json')['repeatedWalkClipping']
        self.assertEqual(len(fixture['clips']),3)
        for case in fixture['clips']:
            frames=[]
            for rows in case['frames']:
                frame=Image.new('RGBA',(32,32))
                for y,row in enumerate(rows):
                    for x in range(32):
                        if int(row,16)&(1<<(31-x)):frame.putpixel((x,y),(50,50,50,255))
                frames.append(frame)
            self.assertEqual(frame_audit(frames,frames[0],'WALK','west','UNKNOWN')['edgeFrames'],case['expectedEdgeFrames'])
            before=[f.tobytes() for f in frames]
            for action in load_quality()['actions']:
                for direction in ('south','north','west','east'):
                    payload=margin_edit_payload(frames,action,direction)
                    self.assertEqual(len(payload['frames']),9)
                    self.assertLessEqual(len(payload['description']),2000)
                    self.assertIn(load_quality()['actions'][action],payload['description'])
                    self.assertIn('ONE transparent pixel at ALL edges',payload['description'])
                    self.assertIn('Do not amputate',payload['description'])
                    for original,item in zip(frames,payload['frames']):
                        decoded=Image.open(io.BytesIO(base64.b64decode(item['image']['base64']))).convert('RGBA')
                        self.assertEqual(decoded.tobytes(),original.tobytes())
            self.assertEqual([f.tobytes() for f in frames],before)
            with self.assertRaises(ValueError):margin_edit_payload(frames,'BASE','west')
            with self.assertRaises(ValueError):margin_edit_payload(frames[:8],'WALK','west')


if __name__=='__main__':unittest.main()
