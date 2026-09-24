import json, math, subprocess, sys, tempfile, unittest
from pathlib import Path
from PIL import Image, ImageDraw
import numpy as np

HARNESS=Path(__file__).resolve().parents[1]/'src/main/resources/motion-harness'

class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        # Draw a fictional test silhouette. No shelter photo or generated customer asset is shipped.
        im=Image.new('RGBA',(64,64));d=ImageDraw.Draw(im)
        d.rectangle((9,26,43,43),fill='#eedbc3');d.rectangle((29,10,55,35),fill='#f7e8d2')
        d.polygon([(30,10),(33,5),(38,11),(49,10),(54,5),(55,15)],fill='#ddc2a1')
        for x,y in [(13,38),(24,38),(32,39),(41,40)]:d.rectangle((x-2,y,x+2,57),fill='#e3c9a8')
        d.rectangle((8,16,12,32),fill='#ddc2a1');d.rectangle((42,17,43,18),fill='#554431')
        im.save(self.root/'base.png');self.base=im
    def tearDown(self):self.temp.cleanup()
    def request(self,body,success=True):
        (self.root/'request.json').write_text(json.dumps(body))
        run=subprocess.run([sys.executable,str(HARNESS/'runner.py'),str(self.root)],capture_output=True,timeout=20)
        if not success:self.assertNotEqual(run.returncode,0);return
        self.assertEqual(run.returncode,0,run.stderr.decode())
        return json.loads((self.root/'response.json').read_text())
    def test_fitted_profile_remains_a_reviewable_proposal(self):
        result=self.request({'mode':'fit'})
        self.assertTrue(result['reviewRequired']);self.assertEqual(result['fitMethod'],'bounding-box-proposal')
        for key,p in result['profile']['paletteSamples'].items():self.assertEqual(result['profile']['palette'][key],list(self.base.getpixel(tuple(p))))
    def test_three_local_motions_use_source_colors_and_common_anchor(self):
        profile=self.request({'mode':'fit'})['profile']
        allowed={tuple(p) for p in np.asarray(self.base).reshape(-1,4) if p[3]}
        for action,duration in [('WALK',30),('RUN',15),('BACK_OFF',70)]:
            result=self.request({'mode':'render','action':action,'profile':profile})
            count=24 if action=='BACK_OFF' else 48
            sheet=Image.open(self.root/'sheet.png');self.assertEqual(sheet.size,(64*count,64))
            self.assertEqual(result['frameCount'],count);self.assertEqual(result['durationMs'],duration)
            self.assertEqual(count*duration,{'WALK':1440,'RUN':720,'BACK_OFF':1680}[action])
            self.assertGreater(len({sheet.crop((64*i,0,64*(i+1),64)).tobytes() for i in range(count)}),count*.75)
            self.assertTrue(all(tuple(p) in allowed for p in np.asarray(sheet).reshape(-1,4) if p[3]))
            for i in range(count):
                frame=sheet.crop((i*64,0,(i+1)*64,64));box=frame.getbbox()
                self.assertGreaterEqual(min(box[0],box[1],64-box[2],64-box[3]),1)
                # Flood only the exterior, not enclosed transparent holes.
                air=frame.getchannel('A').point(lambda a:255 if a else 0).convert('RGB')
                ImageDraw.floodfill(air,(0,0),(127,127,127))
                line=tuple(profile['palette']['LINE'][:3])
                for y in range(1,63):
                    for x in range(1,63):
                        if frame.getpixel((x,y))[3] and any(air.getpixel(p)==(127,127,127) for p in [(x-1,y),(x+1,y),(x,y-1),(x,y+1)]):
                            self.assertEqual(frame.getpixel((x,y))[:3],line,(action,i,x,y))
    def test_walk_extends_under_load_and_flexes_while_lifted(self):
        profile=self.request({'mode':'fit'})['profile']
        self.request({'mode':'render','action':'WALK','profile':profile})
        frames=json.loads((self.root/'rendered/rig.json').read_text())['frames']
        for leg in ['NH','FH']:
            support=[];swing=[]
            for f in frames:
                g=f['legs'][leg];p=[g[k] for k in ['hip','stifle','hock','paw']]
                ratio=math.dist(p[0],p[-1])/sum(math.dist(a,b) for a,b in zip(p,p[1:]))
                (support if g['planted'] else swing).append(ratio)
            self.assertGreaterEqual(min(support),.94);self.assertLessEqual(min(swing),.85)
        self.assertGreaterEqual(min(sum(g['planted'] for g in f['legs'].values()) for f in frames),3)
    def test_running_muzzle_crossing_export_edge_is_preserved_with_one_strip_offset(self):
        # A long snout moves beyond x=63 during RUN. It must not be cut off before alignment.
        ImageDraw.Draw(self.base).rectangle((48,22,61,29),fill='#554431')
        self.base.save(self.root/'base.png')
        profile=self.request({'mode':'fit'})['profile']
        result=self.request({'mode':'render','action':'RUN','profile':profile})
        guide=json.loads((self.root/'rendered/rig.json').read_text())
        self.assertGreater(guide['sourceBounds'][2],63)
        self.assertLess(result['sharedOffsetX'],0)
        sheet=Image.open(self.root/'sheet.png').convert('RGBA')
        for i,source_count in enumerate(guide['sourceOpaqueCounts']):
            frame=sheet.crop((64*i,0,64*(i+1),64))
            self.assertEqual(int(np.count_nonzero(np.asarray(frame)[:,:,3])),source_count)
            b=frame.getbbox();self.assertGreaterEqual(min(b[0],b[1],64-b[2],64-b[3]),1)
        self.assertEqual(max(sheet.crop((64*i,0,64*(i+1),64)).getbbox()[3] for i in range(48)),60)
    def test_motion_too_wide_for_export_still_requires_rig_review(self):
        profile=self.request({'mode':'fit'})['profile']
        profile['legs']['NF'].update(root=[59,37],paw=[60,58])
        self.request({'mode':'render','action':'RUN','profile':profile},False)
    def test_does_not_read_profile_paths_or_execute_unknown_actions(self):
        profile=self.request({'mode':'fit'})['profile'];profile['baseImage']='../../secret.png'
        self.request({'mode':'render','action':'WALK','profile':profile},False)
        self.request({'mode':'render','action':'WALK; touch /tmp/invalid','profile':profile},False)
    def test_rejects_non_finite_and_outside_landmarks(self):
        for value in [float('nan'),100000,-1]:
            profile=self.request({'mode':'fit'})['profile'];profile['legs']['NH']['root']=[value,38]
            self.request({'mode':'render','action':'WALK','profile':profile},False)

if __name__=='__main__':unittest.main()
