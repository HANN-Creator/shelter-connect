"""Local-only, bounded request protocol for Spring Boot. No network or shell input."""
from pathlib import Path
import json,math,sys,subprocess
import numpy as np
from PIL import Image

HERE=Path(__file__).resolve().parent
ROOT=Path(sys.argv[1]).resolve()
request_path=ROOT/'request.json'
assert request_path.stat().st_size<=32768
request=json.loads(request_path.read_text())
base=Image.open(ROOT/'base.png').convert('RGBA')
assert base.size==(64,64)
canonical=json.loads((HERE/'canonical-profile.json').read_text())

def point(value):
    assert isinstance(value,list) and len(value)==2
    assert all(type(x) in (int,float) and math.isfinite(x) and 0<=x<=63 for x in value)
    return value

def profile(value):
    assert isinstance(value,dict) and set(value)<=set(canonical)
    result={'id':'character','baseImage':'base.png','canvas':[64,64],'anchor':[32,60],
        'facing':'right-three-quarter','landmarksSource':'reviewed profile'}
    assert value['baseImage']=='base.png' and value['canvas']==[64,64] and value['anchor']==[32,60]
    for key in ('torsoPivot','neck','tailBase'):result[key]=point(value[key])
    def polygon(v):
        assert isinstance(v,list) and 3<=len(v)<=64
        return [point(p) for p in v]
    result['bodyMask']=polygon(value['bodyMask'])
    assert set(value['parts'])=={'torso','head','tail'}
    result['parts']={key:polygon(value['parts'][key]) for key in ('torso','head','tail')}
    assert set(value['legs'])==set(canonical['legs'])
    result['legs']={}
    for key,rest in canonical['legs'].items():
        leg=value['legs'][key]
        assert set(leg)==set(rest)
        assert all(leg[k]==rest[k] and type(leg[k])==type(rest[k]) for k in ('offset','near','hind'))
        result['legs'][key]={**rest,'paw':point(leg['paw']),'root':point(leg['root'])}
        assert 8<=math.dist(leg['paw'],leg['root'])<=26
    assert set(value['paletteSamples'])==set(canonical['paletteSamples'])
    result['paletteSamples']={k:point(v) for k,v in value['paletteSamples'].items()}
    assert all(all(type(n)==int for n in p) and base.getpixel(tuple(p))[3]==255 for p in result['paletteSamples'].values())
    result['palette']={key:list(base.getpixel(tuple(p))) for key,p in result['paletteSamples'].items()}
    return result

def fit():
    bounds=base.getbbox();assert bounds and bounds[0]>0 and bounds[1]>0 and bounds[2]<64 and bounds[3]<64
    left,top,right,bottom=bounds
    assert 28<=right-left<=58 and 32<=bottom-top<=59
    ref=canonical['referenceBounds']
    def transform(p):
        return [max(1,min(62,round(left+(p[0]-ref[0])*(right-left)/(ref[2]-ref[0])))),
                max(1,min(62,round(top+(p[1]-ref[1])*(bottom-top)/(ref[3]-ref[1]))))]
    proposed={k:v for k,v in canonical.items() if k!='referenceBounds'}
    for key in ('torsoPivot','neck','tailBase'):proposed[key]=transform(proposed[key])
    for key in ('bodyMask',):proposed[key]=[transform(p) for p in proposed[key]]
    proposed['parts']={key:[transform(p) for p in points] for key,points in proposed['parts'].items()}
    proposed['legs']={key:{**leg,'paw':transform(leg['paw']),'root':transform(leg['root'])} for key,leg in proposed['legs'].items()}
    pixels=[(x,y) for y in range(1,63) for x in range(1,63) if base.getpixel((x,y))[3]==255]
    for name,coord in proposed['paletteSamples'].items():
        p=transform(coord);nearest=min(pixels,key=lambda xy:math.dist(p,xy))
        assert math.dist(p,nearest)<=6
        proposed['paletteSamples'][name]=list(nearest)
    proposed=profile(proposed)
    proposed['landmarksSource']='bounding-box proposal; review limbs, masks and coat samples before confirming'
    return {'profile':proposed,'reviewRequired':True,'fitMethod':'bounding-box-proposal'}

if request['mode']=='fit':
    assert set(request)=={'mode'}
    response=fit()
elif request['mode']=='render':
    assert set(request)=={'mode','action','profile'}
    action=request['action'];assert action in ('WALK','RUN','BACK_OFF')
    rig_profile=profile(request['profile'])
    (ROOT/'profile.json').write_text(json.dumps(rig_profile))
    subprocess.run([sys.executable,str(HERE/'render.py'),'--profile',str(ROOT/'profile.json'),
        '--action',action,'--out',str(ROOT/'rendered')],check=True,timeout=45,stdout=subprocess.DEVNULL)
    frames=[Image.open(p).convert('RGBA') for p in sorted((ROOT/'rendered/frames').glob('*.png'))]
    assert len(frames)==24
    boxes=[f.getbbox() for f in frames]
    assert all(b and min(b[0],b[1],64-b[2],64-b[3])>=1 for b in boxes)
    palette={tuple(p) for p in np.asarray(base).reshape(-1,4) if p[3]}
    assert all(tuple(p) in palette for f in frames for p in np.asarray(f).reshape(-1,4) if p[3])
    assert max(b[3] for b in boxes)==60
    sheet=Image.new('RGBA',(64*len(frames),64))
    for i,f in enumerate(frames):sheet.paste(f,(i*64,0))
    sheet.save(ROOT/'sheet.png')
    guide=json.loads((ROOT/'rendered/rig.json').read_text())
    response={'frameCount':24,'durationMs':guide['frameDurationMs'],
        'sharedOffsetX':guide['sharedOffset'][0],'sharedOffsetY':guide['sharedOffset'][1],
        'paletteChecked':True,'boundsChecked':True,'templateVersion':guide['templateVersion']}
else:raise ValueError('Unsupported operation')
(ROOT/'response.json').write_text(json.dumps(response))
