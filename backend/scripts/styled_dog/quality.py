"""Shared, versioned prevention rules and lossless offline quality gates.

Alpha checks are deterministic. This module never claims to visually recognize
tail posture or face direction; those require the server's Luna review.
"""
from pathlib import Path
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
    if tail not in TAILS or not isinstance(issues, list) or len(issues)>6 or any(i not in rules['corrections'] for i in issues):
        raise ValueError('Invalid quality contract or issue codes')
    if quality.get('rulesSha256') and quality['rulesSha256'] != digest(POLICY):
        raise ValueError('Quality rules changed; do not silently resume paid generation')
    parts = [rules['actions'][action], rules['tailCarriage'][tail], rules['commonMotion'],
             'Remain '+direction+' facing in ALL frames including the final hold.']
    if direction == 'north':
        parts.append(rules['rearView'])
    if issues:
        parts.append('CORRECTION: '+' '.join(rules['corrections'][i] for i in sorted(set(issues))))
    return ' '.join(parts)


def frame_audit(frames, seed):
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
    return {'structuralPassed':not edges, 'issues':['CANVAS_CLIPPING'] if edges else [],
            'edgeFrames':edges, 'visualReviewRequired':True, 'qualityRules':quality_binding()}


def audit_run(root):
    """Inspect every RAW frame before holds/packaging can hide a bad final pose."""
    clips = {}
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
        clips[label] = frame_audit([native_image(p.read_bytes()) for p in files], native_image(seed_path.read_bytes()))
    if not clips:
        raise ValueError('No clips to audit')
    passed = all(c['structuralPassed'] for c in clips.values())
    report = {'status':'STRUCTURAL_PASS' if passed else 'REQUIRES_REPAIR', 'clips':clips,
              'visualReviewRequired':True, 'qualityRules':quality_binding()}
    write(root/'quality-audit.json',report)
    return report
