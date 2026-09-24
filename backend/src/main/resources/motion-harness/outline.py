"""Restore cut edges after compositing without growing the sprite silhouette."""
from collections import deque

import numpy as np
from PIL import Image


def restore_outline(image, color, protected=None):
    """Ink the inner pixel beside exterior air, leaving enclosed holes alone.

    Run once on the finished composite: outlining separate parts would draw
    seams through the shoulders and neck. Alpha and interior colors are kept.
    """
    pixels = np.array(image.convert('RGBA'))
    occupied = np.pad(pixels[:, :, 3] > 0, 1)
    exterior = np.zeros_like(occupied)
    exterior[0, 0] = True
    pending = deque([(0, 0)])
    height, width = occupied.shape
    while pending:
        y, x = pending.popleft()
        for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
            if 0 <= ny < height and 0 <= nx < width and not occupied[ny, nx] and not exterior[ny, nx]:
                exterior[ny, nx] = True
                pending.append((ny, nx))
    border = occupied[1:-1, 1:-1] & (
        exterior[:-2, 1:-1] | exterior[2:, 1:-1]
        | exterior[1:-1, :-2] | exterior[1:-1, 2:]
    )
    if protected is not None:
        # Original boundary pixels already carry the artist's line/shading.
        # Only exposed cut edges need new ink; never darken the whole silhouette.
        border &= ~np.asarray(protected, dtype=bool)
    pixels[border, :3] = color[:3]
    return Image.fromarray(pixels)


def source_edges(image):
    """Encode silhouette provenance in RGB while retaining identical alpha."""
    pixels = np.array(image.convert('RGBA'))
    occupied = np.pad(pixels[:, :, 3] > 0, 1)
    interior = occupied[:-2, 1:-1] & occupied[2:, 1:-1] & occupied[1:-1, :-2] & occupied[1:-1, 2:]
    border = occupied[1:-1, 1:-1] & ~interior
    pixels[:, :, :3] = np.where(border[:, :, None], 255, 0)
    return Image.fromarray(pixels)
