"""Lossless PNG sheets and a local, secret-free review bundle."""
from pathlib import Path
import shutil
import zipfile

from PIL import Image, ImageDraw
from .client import digest, native_image, read, write
from .pipeline import require_review, verify_inputs


def sheet_for(frames):
    sheet = Image.new('RGBA',(32*len(frames),32))
    for i,frame in enumerate(frames):
        # Paste without an alpha mask: preserve even partially transparent pixels exactly.
        sheet.paste(frame,(i*32,0))
    return sheet


def gif_for(frames, target, duration, loop):
    images = []
    for frame in frames:
        canvas = Image.new('RGB',(32,32),'#e8f0d8')
        canvas.paste(frame,mask=frame.getchannel('A'))
        images.append(canvas.resize((160,160),Image.Resampling.NEAREST))
    palette_source = Image.new('RGB',(160*len(images),160))
    for i,frame in enumerate(images):
        palette_source.paste(frame,(i*160,0))
    palette = palette_source.quantize(colors=256,dither=Image.Dither.NONE)
    indexed = [f.quantize(palette=palette,dither=Image.Dither.NONE) for f in images]
    durations = [duration]*len(indexed)
    if not loop:
        durations[-1] += 1600
    indexed[0].save(target,save_all=True,append_images=indexed[1:],duration=durations,
                    loop=0,disposal=2,optimize=False)


def package(root):
    import json
    rules = verify_inputs(root)
    require_review(root)
    for name in ('sheets','gifs','contact-sheets'):
        (root/name).mkdir(exist_ok=True)
    animations = {}
    all_frames = {}
    for path in sorted((root/'clips').glob('*.json')):
        clip = read(path)
        label = clip['label']
        frames = [native_image((root/'frames'/label/f'{i:02}.png').read_bytes()) for i in range(clip['frameCount'])]
        if clip['frameCount'] != 9 or clip['frameSha256'] != [digest(root/'frames'/label/f'{i:02}.png') for i in range(9)]:
            raise ValueError('Animation frames changed after generation')
        if clip['sourceSha256'] != digest(root/'directions'/(clip['direction']+'.png')):
            raise ValueError('Animation belongs to a different direction seed')
        seed = native_image((root/'directions'/(clip['direction']+'.png')).read_bytes())
        if frames[0].tobytes() != seed.tobytes():
            raise ValueError('First frame differs from seed')
        path = root/'sheets'/(label+'.png')
        sheet_for(frames).save(path)
        gif_for(frames,root/'gifs'/(label+'.gif'),clip['durationMs'],clip['loop'])
        edges = [i for i,f in enumerate(frames) if any(a == b for a,b in zip(f.getchannel('A').getbbox(),(0,0,32,32)))]
        vector = {'south':(0,1),'north':(0,-1),'west':(-1,0),'east':(1,0)}[clip['direction']]
        factor = -1 if clip['action'] == 'BACK_OFF' else int(clip['action'] in ('WALK','RUN'))
        clip.update(spritesheetUrl='sheets/'+label+'.png',gifUrl='gifs/'+label+'.gif',
            sha256=digest(path),transform={'scale':1,'offset':[0,0],'perFrameCentering':False},
            firstFrameMatchesSeed=True,rawFramesTouchingEdge=edges,
            loopJoinChangedPixels=sum(a != b for a,b in zip(frames[0].get_flattened_data(),frames[-1].get_flattened_data())),
            uniqueFrames=len({f.tobytes() for f in frames}),holdLastFrame=not clip['loop'],
            returnToIdle='DIRECT' if clip['loop'] else 'REVERSE_FRAMES',
            worldMotion={'unitVector':{'x':vector[0]*factor,'y':vector[1]*factor},'speedControlledByFrontend':True},
            frames=[{'x':i*32,'y':0,'width':32,'height':32,'durationMs':clip['durationMs']} for i in range(9)])
        animations[label] = clip
        all_frames[label] = frames
    if not animations:
        raise ValueError('No completed clips to package')
    expected = {a.lower()+'-'+d for a in rules['actions'] for d in rules['directions']}
    ledger = [{k:s.get(k) for k in ('label','endpoint','status','jobId','usage')} for s in
              (read(p) for p in sorted((root/'raw').glob('*-state.json')))]
    # Per-job billing is authoritative; balance can include other account activity.
    usage_complete = bool(ledger) and all(s['status'] == 'COMPLETED' and
        isinstance((s['usage'] or {}).get('generations'), (int,float)) for s in ledger)
    charged = sum(s['usage']['generations'] for s in ledger) if usage_complete else None
    manifest = {'schemaVersion':'cozy32-photo-style-v1','styleVersion':rules['version'],
        'frameSize':{'width':32,'height':32},'anchorPixels':{'x':16,'y':30},
        'generator':rules['characterEndpoint'],'animationProvider':rules['animationEndpoint'],
        'source':read(root/'source.json'),'animations':animations,'directionCount':4,
        'plannedClips':32,'missingClips':sorted(expected-set(animations)),
        'status':'COMPLETED' if set(animations) == expected else 'PARTIAL',
        'visualReviewStatus':'PENDING','published':False,'productionApproved':False,
        'totalFrames':len(animations)*9,'newClips':len(animations),'generationsCharged':charged,
        'jobLedger':ledger,'referenceProvenance':read(root/'reference-provenance.json'),
        'seedReview':read(root/'seed-review.json'),
        'behaviorBasis':'Action demonstrations; not verified temperament or behavior of this dog.'}
    if (root/'quality-review.json').exists():
        manifest['qualityReview'] = read(root/'quality-review.json')
        manifest['visualReviewStatus'] = manifest['qualityReview'].get('status','PENDING')
    write(root/'manifest.json',manifest)
    # Escape HTML-sensitive text before embedding arbitrary public API values in JS.
    data = json.dumps(manifest,ensure_ascii=False).replace('<','\\u003c').replace('>','\\u003e').replace('&','\\u0026').replace('\u2028','\\u2028').replace('\u2029','\\u2029')
    (root/'manifest.js').write_text('window.TRIAL_DATA='+data+';')
    shutil.copyfile(Path(__file__).with_name('preview.html'),root/'index.html')
    for action in rules['actions']:
        contact = Image.new('RGB',(9*96+90,4*112),'#f7f6ee')
        draw = ImageDraw.Draw(contact)
        for row,d in enumerate(rules['directions']):
            draw.text((8,row*112+48),d,fill='#435237')
            for col,frame in enumerate(all_frames.get(action.lower()+'-'+d,[])):
                scaled = frame.resize((96,96),Image.Resampling.NEAREST)
                contact.paste(scaled,(90+col*96,row*112+8),scaled)
        contact.save(root/'contact-sheets'/(action.lower()+'.png'))
    comparison = Image.new('RGB',(960,400),'#f7f6ee')
    draw = ImageDraw.Draw(comparison)
    from PIL import ImageOps
    for i,(name,title) in enumerate([('photo-1.png','Actual photograph'),('style-reference.png','Approved style'),('base.png','New dog')]):
        im = Image.open(root/name).convert('RGBA')
        im = ImageOps.contain(im,(300,340),Image.Resampling.LANCZOS if i == 0 else Image.Resampling.NEAREST)
        comparison.paste(im,(i*320+(320-im.width)//2,45+(340-im.height)//2),im)
        draw.text((i*320+20,20),title,fill='#435237')
    comparison.save(root/'comparison.png')
    (root/'README.md').write_text('# 사진 + 승인 도트 스타일\n\n'
        '백엔드 generate_styled_dog.py로 생성한 검토용 결과. 운영 DB/API 에셋을 교체하지 않았습니다.\n'
        'create-character-pro + animate-pixminimax. 전체 계획 8종 × 4방향. 완료 범위는 manifest.json 참고.\n'
        'PNG 시트는 native 32px 픽셀 그대로이며 GIF는 보기용입니다. imageSmoothingEnabled=false로 정수 배율 재생하세요.\n'
        '고정 기준점 (16,30), 이동은 worldMotion 방향으로 프론트에서 처리합니다.\n'
        'SIT/LIE_DOWN은 마지막 자세 유지; 복귀는 프레임 역순. 모션은 기능 예시이고 실제 성격 판단이 아닙니다.\n'
        'raw/와 plan/은 요청·응답 감사 기록이므로 공개 묶음에서 제외합니다.\n')
    # Explicit allowlist; never include raw requests, balance, keys, logs or arbitrary files.
    files = ['index.html','manifest.js','manifest.json','README.md','source.json','style-reference.png',
             'reference-provenance.json','seed-review.json','rules.json','photo-1.png','photo-2.png',
             'face.png','photo-concept.png','base.png','seed-directions.png','comparison.png','quality-review.json']
    with zipfile.ZipFile(root/'styled-dog-assets.zip','w',zipfile.ZIP_DEFLATED) as z:
        for name in files:
            if (root/name).exists():
                z.write(root/name,name)
        for folder in ('directions','frames','sheets','contact-sheets','gifs'):
            for p in sorted((root/folder).rglob('*')):
                if p.suffix in ('.png','.gif'):
                    z.write(p,p.relative_to(root))
    print(json.dumps({'clips':len(animations),'frames':manifest['totalFrames'],'generations':charged,
                      'status':manifest['status']},ensure_ascii=False))
