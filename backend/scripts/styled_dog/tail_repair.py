"""Native tail-only recovery from a reviewed, complete PixelLab edit.

Never accept a clipped edit by masking it, dropping frame 8 or replacing frame 0.
Masks are specific to reviewed seed/result hashes, not universal dog anatomy.
This module does not publish assets or change a persisted worker job.
"""
import base64
import hashlib
import io
from PIL import Image, ImageOps
from .quality import frame_audit, frontal_tail_frames, load_quality, quality_binding, components


def pixel_hash(image):
    return hashlib.sha256(image.tobytes()).hexdigest()


def sheet_hash(frames):
    return hashlib.sha256(b''.join(f.tobytes() for f in frames)).hexdigest()


def read_sheet(path):
    with Image.open(path) as source:
        if source.size != (288, 32):
            raise ValueError('Expected all nine native 32px frames; never resize')
        sheet = source.convert('RGBA')
    return [sheet.crop((i*32, 0, i*32+32, 32)) for i in range(9)]


def sheet_for(frames):
    sheet = Image.new('RGBA', (288, 32))
    if len(frames) != 9:
        raise ValueError('Expected nine frames')
    for i, frame in enumerate(frames):
        if frame.mode != 'RGBA' or frame.size != (32,32):
            raise ValueError('Expected native RGBA frames')
        sheet.paste(frame, (i*32, 0))
    return sheet


def audit_edit(frames, approved_seed, direction):
    sheet_for(frames)
    if direction not in ('south','north','west','east'):
        raise ValueError('Unsupported direction')
    # Raw frame 0 is checked BEFORE it can be replaced with the approved seed.
    result = frame_audit(frames, frames[0], 'TAIL_WAG', direction, 'LOW')
    upper = frontal_tail_frames(frames, approved_seed, 'TAIL_WAG', direction, 'LOW')
    if upper and 'TAIL_CARRIAGE' not in result['issues']:
        result['issues'].append('TAIL_CARRIAGE')
    result['silhouetteFrames'] = sorted(set(upper + result['silhouetteFrames']))
    result['detachedFrames'] = [i for i,f in enumerate(frames) if len(components(f)) != 1]
    if result['detachedFrames'] and 'DETACHED_PIXELS' not in result['issues']:
        result['issues'].append('DETACHED_PIXELS')
    result['structuralPassed'] = not result['issues']
    return result


def edit_payload(frames, direction):
    # Source is intentionally defective; validate format, not a passing verdict.
    sheet_for(frames)
    if direction not in ('south','north','west','east'):
        raise ValueError('Unsupported direction')
    rules = load_quality()['tailEdit']
    description = rules['common'] + ' ' + rules[direction]
    if len(description) > 2000:
        raise ValueError('Edit prompt exceeds provider limit')
    encoded = []
    for frame in frames:
        buffer = io.BytesIO()
        frame.save(buffer, format='PNG')
        encoded.append({'image':{'type':'base64','base64':base64.b64encode(buffer.getvalue()).decode()},
                        'size':{'width':32,'height':32}})
    return {'description':description,'frames':encoded,'image_size':{'width':32,'height':32},
            'no_background':True,'seed':202610051}


def idle_edit_payload(frames, direction):
    """Edit the entire failed idle once, keeping its exact first frame as reference.

    This creates a provider payload only. It does not mask pixels, drop frames,
    change a stored verdict or reset the server's two-repair budget.
    """
    payload = edit_payload(frames, direction)
    rules = load_quality()
    payload['description'] = (
        'Repair this COMPLETE nine-frame idle animation. Frame zero is the approved standing pose; '
        'match its body height, face, eyes, ears, paws, tail visibility, outline and palette in every frame. '
        'Remove the invented moving appendage above the head when absent in frame zero. '
        'Preserve any genuine visible tail from frame zero, fixed in exactly that pose; do not amputate it. '
        'Restore the same planted paws and standing height; no crouching, steps, bouncing or tail movement. '
        'Allow only a tiny breath or brief natural blink. Keep all nine frames, native crisp pixels, '
        'fixed camera and transparent background. Do not substitute sitting, blur, resize or crop. '
        + rules['actions']['IDLE'] + ' ' + rules['commonMotion']
        + ' Remain ' + direction + ' facing in every frame. '
        + (rules['rearView'] if direction == 'north' else '')
    )
    if len(payload['description']) > 2000:
        raise ValueError('Idle edit prompt exceeds provider limit')
    return payload


def margin_edit_payload(frames, action, direction):
    """Keep the requested action while editing a repeatedly clipped full strip."""
    rules = load_quality()
    if action not in rules['actions']:
        raise ValueError('Unsupported action')
    payload = edit_payload(frames, direction)
    payload['description'] = (
        'Repair clipped extremities in this COMPLETE nine-frame ' + action + ' animation. '
        'Frame zero is the approved identity and standing reference. Preserve its face, eyes, '
        'ears, coat, body size and facing. Restore a complete connected tail tip, paws and muzzle '
        'where clipped; tuck outward reach inward while keeping ONE transparent pixel at ALL edges '
        'in EVERY frame, including the final hold. Keep the tail shape and carriage from frame zero; '
        'if occluded, do not invent a raised tail. Do not amputate the tip, shrink the whole dog, '
        'erase body parts, blur, resize, crop or drop any frame. Keep the requested action and '
        'smooth phase progression; never replace all moving/seated poses with a standing still. '
        'Native 32x32 crisp pixels, original palette, fixed camera, transparent background. '
        + rules['actions'][action] + ' ' + rules['commonMotion']
        + ' Remain ' + direction + ' facing in every frame. '
        + (rules['rearView'] if direction == 'north' else '')
    )
    if len(payload['description']) > 2000:
        raise ValueError('Margin edit prompt exceeds provider limit')
    return payload


def compose_tail(seed, source_seed, frames, mask, review):
    direction = review.get('direction')
    source_direction = review.get('sourceDirection')
    if direction not in ('south','north','west','east'):
        raise ValueError('Unsupported target direction')
    if source_direction != direction and (source_direction,direction) not in (('west','east'),('east','west')):
        raise ValueError('Only opposite side-view tail motion may be mirrored')
    if mask.mode != 'L' or mask.size != (32,32) or not set(mask.tobytes()) <= {0,255} or not mask.getbbox():
        raise ValueError('Expected a nonempty binary native tail mask')
    for original in (seed,source_seed):
        if original.size != (32,32) or original.mode != 'RGBA':
            raise ValueError('Expected native approved seed')
    expected = {'seedPixelSha256':pixel_hash(seed),'sourceSeedPixelSha256':pixel_hash(source_seed),
                'editPixelSha256':sheet_hash(frames),'maskPixelSha256':pixel_hash(mask)}
    if (review.get('approved') is not True or review.get('tailCarriage') != 'LOW'
            or not isinstance(review.get('note'),str) or len(review['note'].strip()) < 20
            or any(review.get(k) != v for k,v in expected.items())):
        raise ValueError('Review must bind this seed, all edited frames and the exact tail mask')
    raw = audit_edit(frames, source_seed, source_direction)
    if not raw['structuralPassed']:
        raise ValueError('Raw edit rejected before compositing: '+', '.join(raw['issues']))
    selected = [ImageOps.mirror(f) for f in frames] if source_direction != direction else frames
    palette = sorted({seed.getpixel((x,y)) for y in range(32) for x in range(32) if seed.getpixel((x,y))[3]})
    if not palette:
        raise ValueError('Empty seed')
    result = []
    for i,frame in enumerate(selected):
        restored = seed.copy()
        if i: # Approved frame 0 is restored only after all raw frames pass.
            for y in range(32):
                for x in range(32):
                    if mask.getpixel((x,y)):
                        pixel = frame.getpixel((x,y))
                        color = min(palette,key=lambda c:sum((c[j]-pixel[j])**2 for j in range(3))) if pixel[3] else (0,0,0,0)
                        restored.putpixel((x,y),color)
        result.append(restored)
    audit = audit_edit(result,seed,direction)
    # Color flicker alone is not a wag. The tail must change its silhouette.
    silhouettes = {bytes(f.getpixel((x,y))[3] for y in range(32) for x in range(32) if mask.getpixel((x,y))) for f in result}
    if len(silhouettes) < 2:
        raise ValueError('No visible tail silhouette motion after restoration')
    if not audit['structuralPassed']:
        raise ValueError('Restored result rejected: '+', '.join(audit['issues']))
    report = {'status':'LOCAL_REVIEW_CANDIDATE','published':False,'visualReviewRequired':True,
              'qualityRules':quality_binding(),'rawAudit':raw,'resultAudit':audit,'review':review,
              'outputPixelSha256':sheet_hash(result),'frameCount':9,'tailSilhouetteCount':len(silhouettes),
              'bodyOutsideMaskUnchanged':True,'originalPaletteOnly':True,
              'sourceDirection':source_direction,'targetDirection':direction,'mirrored':source_direction!=direction}
    return result,report
