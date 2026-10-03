"""Deterministic 64 -> 32 map variant. Palette is derived from this dog's base.

No network, generated colors, dithering, per-frame fit or frame interpolation.
The original 64px canvas and animations remain untouched.
"""
import hashlib
import json
import numpy as np
from PIL import Image

VERSION = 'map-pixel-v1'
WEIGHTS = np.array([.30, .59, .11])


def source_palette(base):
    a = np.asarray(base.convert('RGBA'))
    pixels = a[a[:, :, 3] >= 128][:, :3]
    if len(pixels) < 32 or len(pixels) > 3600:
        raise ValueError('Invalid base silhouette')
    colors, counts = np.unique(pixels, axis=0, return_counts=True)
    values = colors.astype(float)
    light = values @ WEIGHTS
    # Reserve both contrast extremes (including tiny eye glints), then retain
    # prominent and distinct coat colors. Every selected RGB exists in the base.
    selected = list(dict.fromkeys([int(counts.argmax()), int(light.argmin()), int(light.argmax())]))
    while len(selected) < min(16, len(colors)):
        distance = (((values[:, None] - values[selected][None]) ** 2) * WEIGHTS).sum(-1).min(-1)
        score = distance * np.sqrt(counts)
        score[selected] = -1
        selected.append(int(score.argmax()))
    palette = colors[selected]
    visible = a[:, :, 3] >= 128
    p = np.pad(visible, 1)
    edge = visible & ~(p[:-2, 1:-1] & p[2:, 1:-1] & p[1:-1, :-2] & p[1:-1, 2:])
    edges, frequency = np.unique(a[edge][:, :3], axis=0, return_counts=True)
    brightness = edges.astype(float) @ WEIGHTS
    dark = brightness <= np.percentile(brightness, 25)
    outline = edges[np.where(dark, frequency, -1).argmax()].astype(float)
    outline_index = (((palette.astype(float) - outline) ** 2) * WEIGHTS).sum(-1).argmin()
    return palette, int(outline_index)


def convert_frame(frame, palette, outline_index):
    if frame.size != (64, 64):
        raise ValueError('Invalid frame size')
    cells = (np.asarray(frame.convert('RGBA'), dtype=float).reshape(32, 2, 32, 2, 4)
             .transpose(0, 2, 1, 3, 4).reshape(32, 32, 4, 4))
    opaque = cells[..., 3] >= 128
    visible = opaque.sum(-1) >= 2
    if not visible.any():
        raise ValueError('Empty map frame')
    ids = ((((cells[..., :3][:, :, :, None] - palette.astype(float)[None, None, None]) ** 2)
             * WEIGHTS).sum(-1)).argmin(-1)
    votes = np.stack([((ids == i) & opaque).sum(-1) for i in range(len(palette))], -1)
    chosen = votes.argmax(-1)
    nearest = ids[:, :, 3]
    ties = np.take_along_axis(votes, nearest[..., None], -1)[..., 0] == votes.max(-1)
    chosen = np.where(ties, nearest, chosen)
    p = np.pad(visible, 1)
    interior = p[:-2, 1:-1] & p[2:, 1:-1] & p[1:-1, :-2] & p[1:-1, 2:]
    chosen[visible & ~interior] = outline_index
    # Retain bright details only inside high-contrast cells. White fur never
    # receives a brown outline or a cream highlight borrowed from another dog.
    light = cells[..., :3] @ WEIGHTS
    brightest = np.where(opaque, light, -1).argmax(-1)
    high = np.where(opaque, light, -1).max(-1)
    low = np.where(opaque, light, 256).min(-1)
    glint = visible & interior & (high > 195) & (high - low > 110)
    glint_color = np.take_along_axis(ids, brightest[..., None], -1)[..., 0]
    chosen[glint] = glint_color[glint]
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
    palette, outline_index = source_palette(base)
    output = Image.new('RGBA', (32 * count, 32))
    for index in range(count):
        frame = source.crop((64 * index, 0, 64 * (index + 1), 64))
        output.paste(convert_frame(frame, palette, outline_index), (32 * index, 0))
    palette_data = palette.tolist()
    return output, {
        'converterVersion': VERSION, 'frameCount': count, 'width': 32, 'height': 32,
        'anchorPixels': {'x': 16, 'y': 30}, 'palette': palette_data,
        'paletteSha256': hashlib.sha256(json.dumps(palette_data, separators=(',', ':')).encode()).hexdigest(),
        'paletteChecked': True, 'boundsChecked': True, 'transparencyChecked': True,
    }
