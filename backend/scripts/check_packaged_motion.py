"""Run inside the deployment image: python < scripts/check_packaged_motion.py."""
import json, os, subprocess, tempfile, zipfile
from pathlib import Path
from PIL import Image, ImageDraw

with tempfile.TemporaryDirectory(prefix='harness-check-') as folder:
    root=Path(folder)
    with zipfile.ZipFile('/app/app.jar') as jar:
        for name in ['runner.py','render.py','motion-templates.json','canonical-profile.json']:
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
        assert result['frameCount']==24 and Image.open(root/'sheet.png').size==(1536,64)
        print(action,'PASS',result['templateVersion'])
    assert os.getuid()==10001
