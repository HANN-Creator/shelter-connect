"""Approved photo/style separation and native-pixel motion generation."""
import base64
import hashlib
import io
from pathlib import Path
import shutil
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed

from PIL import Image, ImageDraw
from .client import API, digest, download, image_argument, native_image, read, write
from .source import prepare_concept
from .quality import TAILS, POLICY, load_quality, quality_binding, motion_guidance, learned_guidance, frame_audit

STYLE = Path(__file__).resolve().parents[2] / 'asset-styles' / 'cozy32-v1'
FACING = {'south':'facing the viewer, front view', 'north':'facing away, rear view',
          'west':'facing left, left side view', 'east':'facing right, right side view'}


def load_rules():
    rules = read(STYLE / 'rules.json')
    if digest(STYLE / 'style.png') != rules['styleSha256']:
        raise ValueError('Approved style changed; a new reviewed version is required')
    native_image((STYLE / 'style.png').read_bytes())
    return rules


def character_request(root, traits, rules, quality=None):
    identity = traits['identityDescription'].strip()
    if not identity or len(identity) > 850:
        raise ValueError('Identity description must contain 1–850 characters')
    for key in ('motionDescription', 'rearDescription'):
        if not traits.get(key, '').strip() or len(traits[key]) > 300:
            raise ValueError(key + ' must contain 1–300 characters')
    eye_rules = load_quality()['seedEyes']
    description = (
        'Create the photographed rescue dog in the attached PIXEL SPRITE GAME ART STYLE. '
        'The concept shows one dog full-body and face close-up; PHOTOS define IDENTITY: '
        + identity + ' '
        'STYLE defines rounded compact proportions, softly rounded large head, short paws, dark outline, '
        'crisp shaded pixel clusters and stepped highlights. Preserve stylized proportions. '
        + eye_rules['prevention'] + ' '
        'Copy photo ears, coat and markings; never borrow the style dog identity. '
        'Closed neutral mouth, no grin. Four-legged standing pose, low top-down view. '
        'Full character in transparent 32x32. '+load_quality()['seedMargin']+
        ' No text, scenery, collar, props, blur or realistic long legs.')
    attempt = 0
    if quality:
        attempt = quality.get('attempt', 0)
        if type(attempt) is not int or not 0 <= attempt <= 2 or quality.get('rulesSha256') != digest(POLICY):
            raise ValueError('Invalid or stale seed quality policy')
        issues = quality.get('issues', [])
        if not isinstance(issues, list) or len(issues) > 5 or any(i not in ('EYE_READABILITY','EYE_STYLE','EYE_DIRECTION','SEED_IDENTITY','CANVAS_CLIPPING') for i in issues):
            raise ValueError('Invalid seed defect codes')
        if attempt:
            description += ' '+eye_rules['correction']
    if len(description) > 2000:
        raise ValueError('Character prompt exceeds provider limit')
    return {'description':description, 'image_size':{'width':32,'height':32},
            'method':rules['characterMethod'], 'concept_image':image_argument(root/'photo-concept.png'),
            'reference_image':image_argument(root/'style-reference.png'), 'template_id':'dog',
            'view':'low top-down', 'style_description':rules['styleDescription'],
            'seed':(traits['seed'] + 7919 * attempt) % 2147483647, 'no_background':True}


def prepare(root, traits_path):
    rules = load_rules()
    traits = read(traits_path)
    # Immutable inputs bind later stages to the actual photograph, rules and seed.
    provenance = {'animalId':traits['animalId'], 'traitsSha256':digest(traits_path),
                  'styleVersion':rules['version'], 'styleSha256':rules['styleSha256'],
                  'rulesSha256':digest(STYLE/'rules.json'), 'sourcePhotoSha256':traits['sourcePhotoSha256'],
                  'actualPhotoIncluded':True, 'approvedStyleIncluded':True, 'qualityRules':quality_binding(),
                  'conceptRole':'Photographed identity, ears and coat markings only',
                  'styleRole':'Approved rounded proportions, outlines and pixel shading only',
                  'unknownFeatures':traits.get('unknownFeatures', [])}
    if (root/'reference-provenance.json').exists():
        previous = read(root/'reference-provenance.json')
        if any(previous.get(k) != v for k,v in provenance.items()):
            raise ValueError('Prepared inputs changed; use a new run directory')
    prepare_concept(root, traits)
    shutil.copyfile(STYLE/'style.png', root/'style-reference.png')
    if traits_path.resolve() != (root/'traits.json').resolve():
        shutil.copyfile(traits_path, root/'traits.json')
    provenance['conceptSha256'] = digest(root/'photo-concept.png')
    write(root/'reference-provenance.json', provenance)
    shutil.copyfile(STYLE/'rules.json', root/'rules.json')
    shutil.copyfile(POLICY, root/'quality-rules.json')
    request = character_request(root, traits, rules)
    (root/'character-prompt.txt').write_text(request['description'])
    (root/'style-prompt.txt').write_text(request['style_description'])
    write(root/'plan'/'character.json', request)
    return request


def verify_inputs(root):
    provenance = read(root/'reference-provenance.json')
    checks = {'traitsSha256':root/'traits.json', 'styleSha256':root/'style-reference.png',
              'rulesSha256':root/'rules.json', 'conceptSha256':root/'photo-concept.png',
              'sourcePhotoSha256':root/read(root/'traits.json')['sourcePhoto']}
    if any(digest(path) != provenance[key] for key,path in checks.items()):
        raise ValueError('Pipeline inputs changed since preparation')
    rules = load_rules()
    if provenance.get('qualityRules') != quality_binding() or digest(root/'quality-rules.json') != digest(POLICY):
        raise ValueError('Quality rules changed; prepare and review a new run')
    if provenance['styleVersion'] != rules['version'] or provenance['rulesSha256'] != digest(STYLE/'rules.json'):
        raise ValueError('Run uses another pipeline version')
    return rules


def generate_character(root, client):
    rules = verify_inputs(root)
    body = character_request(root, read(root/'traits.json'), rules)
    if body != read(root/'plan'/'character.json'):
        raise ValueError('Character request changed since preparation')
    if not (root/'balance-before.json').exists():
        write(root/'balance-before.json', client.request('GET','balance'))
    result = client.generate('character', rules['characterEndpoint'], body)
    character_id = str(uuid.UUID(result.get('last_response',result)['character_id']))
    write(root/'character.json', client.request('GET','characters/'+character_id))
    archive = root/'character-export.zip'
    if not archive.exists():
        data = download(API+'characters/'+character_id+'/zip', client.context, 20_000_000)
        archive.write_bytes(data)
    folder = root/'directions'
    folder.mkdir(exist_ok=True)
    # Extract exact bounded image members only. Never extractall an external ZIP.
    with zipfile.ZipFile(archive) as z:
        for direction in rules['directions']:
            names = [n for n in z.namelist() if n == 'rotations/'+direction+'.png' or n.endswith('/rotations/'+direction+'.png')]
            if len(names) != 1 or z.getinfo(names[0]).file_size > 100_000:
                raise ValueError('Unexpected character rotation archive')
            native_image(z.read(names[0])).save(folder/(direction+'.png'))
    shutil.copyfile(folder/'south.png', root/'base.png')
    preview = Image.new('RGB',(4*192,224),'#e8f0d8')
    draw = ImageDraw.Draw(preview)
    for i,d in enumerate(rules['directions']):
        frame = Image.open(folder/(d+'.png')).resize((192,192),Image.Resampling.NEAREST)
        preview.paste(frame,(i*192,32),frame)
        draw.text((i*192+12,10),d,fill='#435237')
    preview.save(root/'seed-directions.png')
    write(root/'balance-after.json',client.request('GET','balance'))


def review_binding(root):
    rules = verify_inputs(root)
    return {'animalId':read(root/'source.json')['desertionNo'],
            'provenanceSha256':digest(root/'reference-provenance.json'), 'qualityRules':quality_binding(),
            'directionSha256':{d:digest(root/'directions'/(d+'.png')) for d in rules['directions']}}


def record_review(root, note, tail_carriage=None):
    if tail_carriage not in TAILS:
        raise ValueError('Record one shared tail carriage (UNKNOWN if not visible) before animation')
    if len(note.strip()) < 20:
        raise ValueError('Describe the visual likeness, style, directions and limitations reviewed')
    write(root/'seed-review.json',dict(review_binding(root), approvedForAnimations=True,
          reviewer='operator visual review', note=note, tailCarriage=tail_carriage, productionApproved=False))


def require_review(root):
    review = read(root/'seed-review.json')
    if review.get('tailCarriage') not in TAILS or review.get('approvedForAnimations') is not True or any(review.get(k) != v for k,v in review_binding(root).items()):
        raise ValueError('Missing or stale visual review for these four direction images')


def motion_request(root, action, direction):
    rules = verify_inputs(root)
    traits = read(root/'traits.json')
    require_review(root)
    review = read(root/'seed-review.json')
    return motion_payload(traits, rules, action, direction, image_argument(root/'directions'/(direction+'.png')),
                          {'contract':{'tailCarriage':review['tailCarriage']}, 'rulesSha256':review['qualityRules']['sha256']})


def motion_payload(traits, rules, action, direction, first_frame, quality=None):
    """Shared by the CLI and the durable server worker; no file/network side effects."""
    spec = rules['actions'][action]
    body = {'first_frame':first_frame,
            'frame_count':rules['generatedFrames'], 'seed':traits['seed'],
            'no_background':True, 'enhance_prompt':False, 'direction':direction, 'view':'low top-down',
            'subject_description':traits['rearDescription' if direction == 'north' else 'motionDescription']}
    if spec['loop']:
        body['last_frame'] = body['first_frame']
    quality = quality or {'contract':{'tailCarriage':'UNKNOWN'}}
    motion = motion_guidance(action, direction, quality)
    if spec['loop']:
        motion += ' Loop smoothly to the initial pose.'
    motion += learned_guidance(action, direction, quality)
    if len(motion)>1000:
        raise ValueError('Quality animation prompt exceeds provider limit')
    attempt = quality.get('attempt', 0)
    if type(attempt) is not int or not 0 <= attempt <= 2:
        raise ValueError('Invalid repair attempt')
    body['description'] = motion
    body['seed'] = (traits['seed']+7919*attempt)%2147483647
    body['initial_pose'] = ('Approved four-legged standing dog, '+FACING[direction]+'. Shared tail carriage '+
        quality['contract']['tailCarriage']+'. Preserve exact starting pose and foot baseline.')
    return body


def save_clip(root, action, direction, result):
    rules = verify_inputs(root)
    label = action.lower()+'-'+direction
    frames = result['last_response']['images']
    if len(frames) != rules['returnedFrames']:
        raise ValueError('Expected input + 8 generated frames')
    folder = root/'frames'/label
    folder.mkdir(parents=True,exist_ok=True)
    decoded = []
    for i, frame in enumerate(frames):
        encoded = frame['base64'].split(',')[-1]
        image = native_image(base64.b64decode(encoded,validate=True))
        decoded.append(image)
        image.save(folder/f'{i:02}.png')
    seed = native_image((root/'directions'/(direction+'.png')).read_bytes())
    if seed.tobytes() != decoded[0].tobytes():
        raise ValueError('Provider changed input frame; review before packaging')
    write(root/'clips'/(label+'.json'),{'label':label,'action':action,'direction':direction,
        'frameCount':len(decoded),'durationMs':rules['actions'][action]['durationMs'],
        'loop':rules['actions'][action]['loop'],'sourceSha256':digest(root/'directions'/(direction+'.png')),
        'frameSha256':[digest(folder/f'{i:02}.png') for i in range(len(decoded))]})
    audit = frame_audit(decoded,seed,action,direction,read(root/'seed-review.json')['tailCarriage'])
    write(root/'audits'/(label+'.json'),audit)
    if not audit['structuralPassed']:
        raise ValueError(','.join(audit['issues'])+': raw frames retained for repair, not approved')


def animate(root, client, actions):
    rules = verify_inputs(root)
    require_review(root)
    actions = actions or list(rules['actions'])
    if not actions or len(set(actions)) != len(actions) or any(a not in rules['actions'] for a in actions):
        raise ValueError('Invalid action selection')
    plan = [(a,d) for a in actions for d in rules['directions']]
    for a,d in plan:
        write(root/'plan'/(a.lower()+'-'+d+'.json'), motion_request(root,a,d))
    # At most four accepted jobs; the account used for this pipeline permits four.
    def run(a,d):
        require_review(root)
        result = client.generate(a.lower()+'-'+d,rules['animationEndpoint'],motion_request(root,a,d))
        save_clip(root,a,d,result)
    failures = []
    with ThreadPoolExecutor(max_workers=4) as pool:
        pending = {pool.submit(run,a,d):(a,d) for a,d in plan}
        for future in as_completed(pending):
            try:
                future.result()
            except Exception as error:
                failures.append({'clip':list(pending[future]),'error':str(error)})
                print('CLIP FAILED',pending[future],str(error),flush=True)
    write(root/'balance-after.json',client.request('GET','balance'))
    write(root/'failures.json',failures)
    if failures:
        raise RuntimeError('Some clips need inspection; paid requests were not duplicated')
