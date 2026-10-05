"""Shared, versioned prevention rules and lossless offline quality gates.

Alpha and a narrowly scoped frontal silhouette check are deterministic.
General anatomy and face direction still require visual review.
"""
from pathlib import Path
from PIL import ImageFilter
from .client import digest, native_image, read, write

POLICY = Path(__file__).resolve().parents[2]/'asset-styles/cozy32-v1/quality-rules.json'
TAILS = ('LOW', 'LEVEL', 'HIGH', 'CURLED', 'UNKNOWN')
DIRECTIONS = ('south', 'north', 'west', 'east')


def load_quality():
    return read(POLICY)


def quality_binding():
    return {'revision': load_quality()['revision'], 'sha256': digest(POLICY)}


def motion_guidance(action, direction, quality):
    rules = load_quality()
    if direction not in DIRECTIONS or action not in rules['actions']:
        raise ValueError('Unsupported quality action/direction')
    tail = quality['contract']['tailCarriage']
    issues = quality.get('issues', [])
    if tail not in TAILS or not isinstance(issues, list) or len(issues)>len(rules['corrections']) or any(i not in rules['corrections'] for i in issues):
        raise ValueError('Invalid quality contract or issue codes')
    if quality.get('rulesSha256') and quality['rulesSha256'] != digest(POLICY):
        raise ValueError('Quality rules changed; do not silently resume paid generation')
    tail_prompt = rules['frontalLowTailPrompt'] if (action,direction,tail)==('TAIL_WAG','south','LOW') else rules['tailCarriage'][tail]
    if action == 'IDLE':
        tail_prompt = rules['idleTailCarriage'][tail]
    elif 'IDLE_MOTION' in issues:
        raise ValueError('IDLE motion correction is only valid for IDLE')
    parts = [rules['actions'][action], tail_prompt, rules['commonMotion'],
             'Remain '+direction+' facing in ALL frames including the final hold.']
    if direction == 'north':
        parts.append(rules['rearView'])
    if issues:
        parts.append('CORRECTION: '+' '.join(rules['corrections'][i] for i in sorted(set(issues))))
    return ' '.join(parts)


def frontal_tail_frames(frames, seed, action, direction, tail):
    """Reject clear new upper appendages in a stationary LOW-tail frontal wag.

    This is not general tail segmentation. It deliberately excludes bowing,
    walking and non-LOW tails; one-pixel outline jitter is tolerated.
    """
    if (action,direction,tail) != ('TAIL_WAG','south','LOW'):
        return []
    rules=load_quality()['frontalLowTail']
    alpha=seed.getchannel('A')
    box=alpha.getbbox()
    if box is None:
        raise ValueError('Empty seed')
    cutoff=box[1]+(box[3]-box[1])*rules['upperBandPercent']//100
    allowed=alpha.point(lambda value:255 if value else 0).filter(
        ImageFilter.MaxFilter(2*rules['seedTolerancePixels']+1))
    return [i for i,frame in enumerate(frames)
            if sum(frame.getpixel((x,y))[3]>0 and allowed.getpixel((x,y))==0
                   for y in range(cutoff) for x in range(32))>=rules['minimumNewPixels']]


def components(frame):
    """8-connectivity permits diagonal pixel outlines but rejects floating debris."""
    pixels = {(x,y) for y in range(32) for x in range(32) if frame.getpixel((x,y))[3]}
    sizes = []
    while pixels:
        stack = [pixels.pop()]
        size = 0
        while stack:
            x,y = stack.pop()
            size += 1
            for dx in (-1,0,1):
                for dy in (-1,0,1):
                    point = (x+dx,y+dy)
                    if point in pixels:
                        pixels.remove(point)
                        stack.append(point)
        sizes.append(size)
    return sorted(sizes, reverse=True)


def idle_motion_frames(frames, seed, action):
    """Bound quiet IDLE silhouette motion, without guessing where a dog's tail is.

    Both extension and retraction are checked against the approved pose. Blinks,
    color-only breathing and one-pixel outline motion are allowed. This cannot
    identify a wag contained entirely inside the body; vision checks that too.
    """
    if action != 'IDLE':
        return []
    rules = load_quality()['idleMotion']
    radius = rules['seedTolerancePixels']
    original = seed.getchannel('A').point(lambda value: 255 if value else 0)
    allowed = original.filter(ImageFilter.MaxFilter(2 * radius + 1))
    failed = []
    for i, frame in enumerate(frames):
        alpha = frame.getchannel('A').point(lambda value: 255 if value else 0)
        expanded = alpha.filter(ImageFilter.MaxFilter(2 * radius + 1))
        changed = sum(bool(alpha.getpixel((x, y))) and not allowed.getpixel((x, y))
                      or bool(original.getpixel((x, y))) and not expanded.getpixel((x, y))
                      for y in range(32) for x in range(32))
        if changed >= rules['minimumChangedPixels']:
            failed.append(i)
    return failed



def frame_audit(frames, seed, action=None, direction=None, tail=None):
    if len(frames) != 9 or frames[0].tobytes() != seed.tobytes():
        raise ValueError('Expected nine frames and an unchanged approved first frame')
    edges = []
    for i, frame in enumerate(frames):
        if frame.size != (32,32) or frame.mode != 'RGBA':
            raise ValueError('Expected native 32px RGBA; never resize to pass')
        box = frame.getchannel('A').getbbox()
        if box is None:
            raise ValueError('Empty frame')
        if box[0] == 0 or box[1] == 0 or box[2] == 32 or box[3] == 32:
            edges.append(i)
    upper=frontal_tail_frames(frames,seed,action,direction,tail)
    idle=idle_motion_frames(frames,seed,action)
    detached=[i for i,frame in enumerate(frames) if len(components(frame))!=1] if action=='TAIL_WAG' else []
    issues=(['CANVAS_CLIPPING'] if edges else [])+(['TAIL_CARRIAGE'] if upper else [])+(['DETACHED_PIXELS'] if detached else [])+(['IDLE_MOTION'] if idle else [])
    return {'structuralPassed':not issues, 'issues':issues, 'silhouetteFrames':upper,
            'idleMotionFrames':idle, 'edgeFrames':edges, 'detachedFrames':detached, 'visualReviewRequired':True, 'qualityRules':quality_binding()}


def audit_run(root):
    """Inspect every RAW frame before holds/packaging can hide a bad final pose."""
    clips = {}
    review=read(root/'seed-review.json') if (root/'seed-review.json').exists() else {}
    for path in sorted((root/'clips').glob('*.json')):
        clip = read(path); label = clip['label']
        if label != clip['action'].lower()+'-'+clip['direction'] or clip['action'] not in load_quality()['actions'] or clip['direction'] not in DIRECTIONS:
            raise ValueError('Invalid clip identity')
        files = [root/'frames'/label/f'{i:02}.png' for i in range(9)]
        if clip['frameCount'] != 9 or clip['frameSha256'] != [digest(p) for p in files]:
            raise ValueError('Raw frames changed after generation')
        seed_path = root/'directions'/(clip['direction']+'.png')
        if clip['sourceSha256'] != digest(seed_path):
            raise ValueError('Direction seed changed')
        if clip['action']=='TAIL_WAG' and clip['direction']=='south' and review.get('tailCarriage') not in TAILS:
            raise ValueError('Shared tail review required for frontal wag audit')
        clips[label] = frame_audit([native_image(p.read_bytes()) for p in files], native_image(seed_path.read_bytes()),
                                  clip['action'],clip['direction'],review.get('tailCarriage'))
    if not clips:
        raise ValueError('No clips to audit')
    passed = all(c['structuralPassed'] for c in clips.values())
    report = {'status':'STRUCTURAL_PASS' if passed else 'REQUIRES_REPAIR', 'clips':clips,
              'visualReviewRequired':True, 'qualityRules':quality_binding()}
    write(root/'quality-audit.json',report)
    return report
