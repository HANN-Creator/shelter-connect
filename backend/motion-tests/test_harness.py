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
        for action,duration in [('WALK',60),('RUN',30),('BACK_OFF',70)]:
            result=self.request({'mode':'render','action':action,'profile':profile})
            sheet=Image.open(self.root/'sheet.png');self.assertEqual(sheet.size,(1536,64))
            self.assertEqual(result['frameCount'],24);self.assertEqual(result['durationMs'],duration)
            self.assertTrue(all(tuple(p) in allowed for p in np.asarray(sheet).reshape(-1,4) if p[3]))
            for i in range(24):
                box=sheet.crop((i*64,0,(i+1)*64,64)).getbbox()
                self.assertGreaterEqual(min(box[0],box[1],64-box[2],64-box[3]),1)
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
    def test_does_not_read_profile_paths_or_execute_unknown_actions(self):
        profile=self.request({'mode':'fit'})['profile'];profile['baseImage']='../../secret.png'
        self.request({'mode':'render','action':'WALK','profile':profile},False)
        self.request({'mode':'render','action':'WALK; touch /tmp/invalid','profile':profile},False)
    def test_rejects_non_finite_and_outside_landmarks(self):
        for value in [float('nan'),100000,-1]:
            profile=self.request({'mode':'fit'})['profile'];profile['legs']['NH']['root']=[value,38]
            self.request({'mode':'render','action':'WALK','profile':profile},False)

if __name__=='__main__':unittest.main()
