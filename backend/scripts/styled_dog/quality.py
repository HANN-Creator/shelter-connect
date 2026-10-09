"""Shared, versioned prevention rules and lossless offline quality gates.

Alpha and a narrowly scoped frontal silhouette check are deterministic.
General anatomy and face direction still require visual review.
"""
from pathlib import Path
import re
from PIL import ImageFilter
from .client import digest, native_image, read, write

POLICY = Path(__file__).resolve().parents[2]/'asset-styles/cozy32-v1/quality-rules.json'
TAILS = ('LOW', 'LEVEL', 'HIGH', 'CURLED', 'UNKNOWN')
DIRECTIONS = ('south', 'north', 'west', 'east')


def learned_guidance(action, direction, quality):
    """Validated runtime lessons are additive prompt data, never Python or shell code."""
    lessons = quality.get('lessons', [])
    if not isinstance(lessons, list):
        raise ValueError('Invalid learned lessons')
    text=[]
    for lesson in lessons:
        if (not isinstance(lesson, dict) or lesson.get('action') != action or lesson.get('direction') != direction
            or lesson.get('tail') != quality['contract']['tailCarriage'] or lesson.get('rulesSha256') != digest(POLICY)
            or lesson.get('issue') not in load_quality()['corrections']
            or (action != 'IDLE' and lesson.get('issue') == 'IDLE_MOTION')
            or not re.fullmatch(r'[a-f0-9]{64}', str(lesson.get('sha256', '')))
            or not re.fullmatch(r'[a-f0-9-]{36}', str(lesson.get('id', '')))):
            raise ValueError('Stale or mismatched learned lesson')
        for field, minimum, maximum in [('prevention',15,120),('criterion',20,240)]:
            value=lesson.get(field)
            if not isinstance(value,str) or not minimum <= len(value) <= maximum or not re.fullmatch(r"[A-Za-z ,.;:'()!?-]+", value):
                raise ValueError('Invalid learned lesson text')
        if lesson['prevention'] not in text:
            text.append(lesson['prevention'])
    return ' Lessons: '+' '.join(text) if text else ''


def learned_seed_guidance(quality):
    lessons = quality.get('lessons', [])
    if not isinstance(lessons, list):
        raise ValueError('Invalid seed lessons')
    text = []
    for lesson in lessons:
        if (not isinstance(lesson, dict) or lesson.get('action') != 'BASE' or lesson.get('direction') != 'all'
            or lesson.get('tail') != 'UNKNOWN' or lesson.get('rulesSha256') != digest(POLICY)
            or lesson.get('issue') not in ('EYE_READABILITY','EYE_STYLE','EYE_DIRECTION','SEED_IDENTITY','CANVAS_CLIPPING','SEED_MOTION_MARGIN')
            or not re.fullmatch(r'[a-f0-9]{64}', str(lesson.get('sha256', '')))
            or not re.fullmatch(r'[a-f0-9-]{36}', str(lesson.get('id', '')))):
            raise ValueError('Stale or mismatched seed lesson')
        for field, low, high in [('prevention',15,120),('criterion',20,240)]:
            value = lesson.get(field)
            if not isinstance(value,str) or not low <= len(value) <= high or not re.fullmatch(r"[A-Za-z ,.;:'()!?-]+", value):
                raise ValueError('Invalid seed lesson text')
        if lesson['prevention'] not in text:
            text.append(lesson['prevention'])
    return ' Lessons: ' + ' '.join(text) if text else ''


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
    if quality.get('recoveryVersion') == rules['recovery']['version']:
        parts = [part.replace('32x32', '40x40') for part in parts if part != rules['commonMotion']]
        parts.append('Same native dog size, coat, eyes, crisp palette; fixed camera, no props/text.')
        parts += [rules['recovery']['frontOcclusion'], rules['recovery']['motionCanvas']]
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
                   for y in range(cutoff) for x in range(frame.width))>=rules['minimumNewPixels']]


def components(frame):
    """8-connectivity permits diagonal pixel outlines but rejects floating debris."""
    pixels = {(x,y) for y in range(frame.height) for x in range(frame.width) if frame.getpixel((x,y))[3]}
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
                      for y in range(frame.height) for x in range(frame.width))
        if changed >= rules['minimumChangedPixels']:
            failed.append(i)
    return failed



def pixel_evidence(frames):
    """Exact visible changes, shared with server vision input; not a quality verdict."""
    if len(frames) != 9 or any(f.size not in ((32,32),(40,40)) or f.size != frames[0].size or f.mode != 'RGBA' for f in frames):
        raise ValueError('Expected nine native 32px RGBA frames')
    data = [list(f.getdata()) for f in frames]
    first = data[0]
    alpha_first, alpha_previous, rgb_first, rgb_previous, rgb_changed, bounds = [], [], [], [], [], []
    for i, (frame, pixels) in enumerate(zip(frames, data)):
        previous = data[max(0, i-1)]
        alpha_first.append(sum(p[3] != b[3] for p, b in zip(pixels, first)))
        alpha_previous.append(sum(p[3] != b[3] for p, b in zip(pixels, previous)))
        def differences(reference):
            return [max(abs(p[c]-b[c]) for c in range(3)) for p, b in zip(pixels, reference) if p[3] or b[3]]
        delta = differences(first)
        rgb_first.append(max(delta, default=0))
        rgb_previous.append(max(differences(previous), default=0))
        rgb_changed.append(sum(d != 0 for d in delta))
        bounds.append(list(frame.getchannel('A').getbbox() or (-1, -1, -1, -1)))
    stable, maximum = not any(alpha_first), max(rgb_first)
    return {'version':'native-frame-delta-v1', 'reference':'frame0', 'frameCount':9,
            'alphaStable':stable, 'maxVisibleRgbDelta':maximum,
            'subtleShadingOnly':stable and all(b[0]>=0 for b in bounds) and maximum<=load_quality()['pixelEvidence']['subtleRgbChannelDelta'],
            'alphaChangedFromFrame0':alpha_first, 'alphaChangedFromPrevious':alpha_previous,
            'maxRgbDeltaFromFrame0':rgb_first, 'maxRgbDeltaFromPrevious':rgb_previous,
            'rgbChangedPixelsFromFrame0':rgb_changed, 'boundsExclusive':bounds}


def frontal_head_growth(frames,seed):
    rules=load_quality()['recovery']['frontalHeadGrowth'];alpha=seed.getchannel('A');left,top,right,bottom=alpha.getbbox()
    inset=(right-left)*rules['horizontalInsetPercent']//100;cutoff=top+(bottom-top)*rules['upperBandPercent']//100
    allowed=alpha.filter(ImageFilter.MaxFilter(2*rules['seedTolerancePixels']+1))
    return [i for i,f in enumerate(frames) if sum(f.getpixel((x,y))[3]>0 and not allowed.getpixel((x,y))
        for y in range(cutoff) for x in range(left+inset,right-inset))>=rules['minimumNewPixels']]


def frame_audit(frames, seed, action=None, direction=None, tail=None):
    if len(frames) != 9:
        raise ValueError('Expected nine frames')
    unchanged = frames[0].tobytes() == seed.tobytes()
    if seed.size == (32,32) and not unchanged:
        raise ValueError('Legacy32 requires an unchanged approved first frame')
    edges = []
    for i, frame in enumerate(frames):
        if frame.size not in ((32,32),(40,40)) or frame.size != seed.size or frame.mode != 'RGBA':
            raise ValueError('Expected native 32px RGBA; never resize to pass')
        box = frame.getchannel('A').getbbox()
        if box is None:
            raise ValueError('Empty frame')
        if box[0] == 0 or box[1] == 0 or box[2] == frame.width or box[3] == frame.height:
            edges.append(i)
    upper=frontal_tail_frames(frames,seed,action,direction,tail)
    if seed.size==(40,40) and direction=='south': upper=sorted(set(upper+frontal_head_growth(frames,seed)))
    idle=idle_motion_frames(frames,seed,action)
    detached=[i for i,frame in enumerate(frames) if len(components(frame))!=1] if action=='TAIL_WAG' else []
    issues=(['CANVAS_CLIPPING'] if edges else [])+(['TAIL_CARRIAGE'] if upper else [])+(['DETACHED_PIXELS'] if detached else [])+(['IDLE_MOTION'] if idle else [])
    if seed.size==(40,40) and action=='WALK' and max(pixel_evidence(frames)['alphaChangedFromFrame0'])<5: issues.append('ACTION_MISSING')
    return {'structuralPassed':not issues, 'issues':issues, 'silhouetteFrames':upper, 'firstFrameUnchanged':unchanged,
            'idleMotionFrames':idle, 'edgeFrames':edges, 'detachedFrames':detached, 'visualReviewRequired':True, 'qualityRules':quality_binding(),
            'pixelEvidence':pixel_evidence(frames)}


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


def seed_margin_audit(seeds):
    """Measure original pixels, never shrink or crop to manufacture clearance."""
    minimum = load_quality()['seedMotionMargin']['minimumClearPixels']
    margins, failed = {}, []
    for direction in DIRECTIONS:
        seed = seeds[direction]
        if seed.mode != 'RGBA' or seed.size != (32, 32):
            raise ValueError('Expected native RGBA seeds')
        box = seed.getchannel('A').getbbox()
        margins[direction] = [box[0], box[1], 32-box[2], 32-box[3]] if box else [0, 0, 0, 0]
        if not box or min(margins[direction]) < minimum:
            failed.append(direction)
    return {'minimumClearPixels': minimum, 'marginDirections': failed,
            'clearPixelsLeftTopRightBottom': margins,
            'issues': ['SEED_MOTION_MARGIN'] if failed else []}


def align_seed(seed):
    """Losslessly position an unapproved seed inside the safe area, if its full extent fits."""
    from PIL import Image
    if seed.mode != 'RGBA' or seed.size != (32, 32): raise ValueError('Expected native RGBA seed')
    margin=load_quality()['seedMotionMargin']['minimumClearPixels'];box=seed.getchannel('A').getbbox()
    if not box or box[2]-box[0]>32-2*margin or box[3]-box[1]>32-2*margin: return seed.copy(),0,0
    dx=max(margin-box[0],min(0,32-margin-box[2]));dy=max(margin-box[1],min(0,32-margin-box[3]))
    if not dx and not dy: return seed.copy(),0,0
    aligned=Image.new('RGBA',(32,32));aligned.paste(seed.crop(box),(box[0]+dx,box[1]+dy))
    return aligned,dx,dy
