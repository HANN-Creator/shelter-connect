"""Run inside the deployment image: python < scripts/check_packaged_motion.py."""
import hashlib, json, os, subprocess, tempfile, zipfile
from pathlib import Path
from PIL import Image, ImageDraw

# Verify the deployment JAR, not Gradle's unfiltered source-tree resources.
# No provider credentials or network are used: this only constructs payloads.
with tempfile.TemporaryDirectory(prefix='styled-package-check-') as folder:
    root=Path(folder)
    names=['scripts/styled_dog/'+name+'.py' for name in
           ['__init__','client','source','pipeline','quality','tail_repair','server_bridge']]
    names += ['asset-styles/cozy32-v1/'+name for name in
              ['style.png','rules.json','quality-rules.json','tail-review-rubric.png','tail-review-rubric.json']]
    with zipfile.ZipFile('/app/app.jar') as jar:
        for name in names:
            target=root/name;target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(jar.read('BOOT-INF/classes/styled-pipeline/'+name))
    style=root/'asset-styles/cozy32-v1'
    quality_bytes=(style/'quality-rules.json').read_bytes()
    quality=json.loads(quality_bytes)
    rules_sha=hashlib.sha256(quality_bytes).hexdigest()
    assert rules_sha==os.environ['EXPECTED_QUALITY_RULES_SHA256'], 'Deployment quality rules differ from checked source'
    assert hashlib.sha256((style/'style.png').read_bytes()).hexdigest()==json.loads((style/'rules.json').read_text())['styleSha256']
    assert hashlib.sha256((style/'tail-review-rubric.png').read_bytes()).hexdigest()==quality['recovery']['tailAnatomyRubricSha256']
    work=root/'run';work.mkdir()
    seed=Image.new('RGBA',(32,32));ImageDraw.Draw(seed).rectangle((8,8,23,23),fill='#eedbc3');seed.save(work/'seed.png')
    for request in [
        {'mode':'motion','action':'WALK','direction':'north',
         'traits':{'seed':1,'motionDescription':'fictional dog','rearDescription':'fictional dog seen from behind'}},
        {'mode':'seed-idle','direction':'west','seed':1},
    ]:
        (work/'input.json').write_text(json.dumps(request))
        subprocess.run(['/opt/motion/bin/python','-m','styled_dog.server_bridge',str(work)],cwd=root/'scripts',
            env={'LANG':'C.UTF-8','PYTHONDONTWRITEBYTECODE':'1'},check=True,capture_output=True,timeout=45)
        payload=json.loads((work/'payload.json').read_text())
        assert isinstance(payload,dict) and payload
        print('STYLED_PAYLOAD',request['mode'],'PASS')
    print('PACKAGED_QUALITY_RULES',quality['revision'],rules_sha,'PASS')

with tempfile.TemporaryDirectory(prefix='harness-check-') as folder:
    root=Path(folder)
    with zipfile.ZipFile('/app/app.jar') as jar:
        for name in ['runner.py','render.py','outline.py','limb_art.py','map_pixels.py','motion-templates.json','canonical-profile.json']:
            (root/name).write_bytes(jar.read('BOOT-INF/classes/motion-harness/'+name))
    # Fictional silhouette; no photo, provider credential or external call.
    im=Image.new('RGBA',(64,64));d=ImageDraw.Draw(im)
    d.rectangle((9,26,43,43),fill='#eedbc3');d.rectangle((29,10,55,35),fill='#f7e8d2')
    d.polygon([(30,10),(33,5),(38,11),(49,10),(54,5),(55,15)],fill='#ddc2a1')
    for x,y in [(13,38),(24,38),(32,39),(41,40)]:d.rectangle((x-2,y,x+2,57),fill='#e3c9a8')
    d.rectangle((8,16,12,32),fill='#ddc2a1');im.save(root/'base.png')
    def run(request):
        (root/'request.json').write_text(json.dumps(request))
        subprocess.run(['/opt/motion/bin/python',str(root/'runner.py'),str(root)],
            env={'LANG':'C.UTF-8','OPENBLAS_NUM_THREADS':'1','PYTHONDONTWRITEBYTECODE':'1'},
            check=True,capture_output=True,timeout=60)
        return json.loads((root/'response.json').read_text())
    profile=run({'mode':'fit'})['profile']
    for action in ['WALK','RUN','BACK_OFF']:
        result=run({'mode':'render','action':action,'profile':profile})
        count=24 if action=='BACK_OFF' else 48
        assert result['frameCount']==count and Image.open(root/'sheet.png').size==(64*count,64)
        print(action,'PASS',result['templateVersion'])
        (root/'source.png').write_bytes((root/'sheet.png').read_bytes())
        mapped=run({'mode':'map_pixels','frameCount':count})
        assert Image.open(root/'sheet.png').size==(32*count,32)
        assert mapped['converterVersion']=='map-pixel-v2'
        assert mapped['frameCount']==count and mapped['paletteChecked'] and mapped['transparencyChecked']
        print(action,'MAP_32 PASS',mapped['converterVersion'])
    assert os.getuid()==10001
