"""Deterministic 64 -> 32 map variant. Palette is derived from this dog's base.

No network, generated colors, dithering, per-frame fit or frame interpolation.
The original 64px canvas and animations remain untouched.
"""
import hashlib
import json
import numpy as np
from PIL import Image

VERSION = 'map-pixel-v2'
WEIGHTS = np.array([.30, .59, .11])


def source_palette(base):
    a = np.asarray(base.convert('RGBA'))
    pixels = a[a[:, :, 3] >= 128][:, :3]
    if len(pixels) < 32 or len(pixels) > 3600:
        raise ValueError('Invalid base silhouette')
    colors, counts = np.unique(pixels, axis=0, return_counts=True)
    values = colors.astype(float)
    light = values @ WEIGHTS
    # Reserve both contrast extremes, then retain
    # prominent and distinct coat colors. Every selected RGB exists in the base.
    selected = list(dict.fromkeys([int(counts.argmax()), int(light.argmin()), int(light.argmax())]))
    while len(selected) < min(16, len(colors)):
        distance = (((values[:, None] - values[selected][None]) ** 2) * WEIGHTS).sum(-1).min(-1)
        score = distance * np.sqrt(counts)
        score[selected] = -1
        selected.append(int(score.argmax()))
    return colors[selected]


def convert_frame(frame, palette):
    if frame.size != (64, 64):
        raise ValueError('Invalid frame size')
    cells = (np.asarray(frame.convert('RGBA'), dtype=float).reshape(32, 2, 32, 2, 4)
             .transpose(0, 2, 1, 3, 4).reshape(32, 32, 4, 4))
    opaque = cells[..., 3] >= 128
    visible = opaque.sum(-1) >= 2
    if not visible.any():
        raise ValueError('Empty map frame')
    # Preserve the area occupied by light/dark details in each fixed source cell.
    # Enlarging one white glint to an entire target pixel makes eyes stare;
    # stamping a global outline thickens the muzzle and joins narrow legs.
    # Quantize the opaque-area mean back to this dog's palette instead. No
    # semantic landmarks, dog-specific coordinates or per-frame fitting.
    mean = ((cells[..., :3] * opaque[..., None]).sum(-2)
            / np.maximum(opaque.sum(-1), 1)[..., None])
    chosen = ((((mean[:, :, None] - palette.astype(float)[None, None]) ** 2)
               * WEIGHTS).sum(-1)).argmin(-1)
    result = np.zeros((32, 32, 4), dtype=np.uint8)
    result[:, :, :3] = palette[chosen]
    result[:, :, 3] = visible * 255
    result[~visible, :3] = 0
    return Image.fromarray(result)


def convert_sheet(base, source, count):
    if type(count) is not int or not 1 <= count <= 48:
        raise ValueError('Invalid frame count')
    if base.size != (64, 64) or source.size != (64 * count, 64):
        raise ValueError('Invalid source dimensions')
    palette = source_palette(base)
    output = Image.new('RGBA', (32 * count, 32))
    for index in range(count):
        frame = source.crop((64 * index, 0, 64 * (index + 1), 64))
        output.paste(convert_frame(frame, palette), (32 * index, 0))
    palette_data = palette.tolist()
    return output, {
        'converterVersion': VERSION, 'frameCount': count, 'width': 32, 'height': 32,
        'anchorPixels': {'x': 16, 'y': 30}, 'palette': palette_data,
        'paletteSha256': hashlib.sha256(json.dumps(palette_data, separators=(',', ':')).encode()).hexdigest(),
        'paletteChecked': True, 'boundsChecked': True, 'transparencyChecked': True,
    }
