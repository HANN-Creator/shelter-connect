"""Deform source pixels along reviewed bones; never repaint a dog's coat.

The rest root/paw landmarks partition the visible legs. This is a geometric
cutout, not anatomy recognition: the existing rig review still owns placement.
Nearest-neighbour sampling retains the source palette and toe markings.
"""
import math
import numpy as np
from PIL import Image


def normal(axis):
    return np.array([-axis[1], axis[0]])


class LimbArt:
    def __init__(self, base, legs):
        self.size = base.size
        pixels = np.asarray(base)
        yy, xx = np.indices(pixels.shape[:2])
        grid = np.stack((xx, yy), axis=-1)
        names = list(legs)
        distances = []
        for name in names:
            root = np.array(legs[name]['root'], float)
            paw = np.array(legs[name]['paw'], float)
            axis = paw - root
            t = np.clip(((grid - root) @ axis) / (axis @ axis), 0, 1)
            distance = np.linalg.norm(grid - (root + t[..., None] * axis), axis=-1)
            valid = (yy >= root[1] - 2) & (distance <= 9)
            distances.append(np.where(valid, distance, np.inf))
        distances = np.stack(distances)
        owner = np.argmin(distances, axis=0)
        self.sources = {}
        for i, name in enumerate(names):
            root = np.array(legs[name]['root'], float)
            paw = np.array(legs[name]['paw'], float)
            length = np.linalg.norm(paw - root)
            axis = (paw - root) / length
            visible = (owner == i) & np.isfinite(distances[i]) & (pixels[..., 3] > 0)
            assert np.count_nonzero(visible) >= 8, 'Review source leg cutout'
            source = np.zeros_like(pixels)
            source[visible] = pixels[visible]
            # Keep the final four rows as a rigid paw so toes do not stretch.
            foot = ((grid - root) @ axis) >= length - 3
            shaft = source.copy(); shaft[((grid - root) @ axis) > length - 2] = 0
            toes = source.copy(); toes[~foot] = 0
            self.sources[name] = (root, paw, length, axis, shaft, toes)

    def render(self, name, joints, paw_angle=0):
        root, rest_paw, length, rest_axis, shaft, toes = self.sources[name]
        points = np.array(joints, float)
        # Piecewise rest coordinates keep markings on the same limb section.
        fractions = np.array([0, .5, 1] if len(points) == 3 else [0, .42, .76, 1])
        paw_target = points[-1].copy()
        angle = math.radians(paw_angle); c, s = math.cos(angle), math.sin(angle)
        rotation = np.array([[c, -s], [s, c]])
        # Meet the unchanged paw at its ankle instead of stretching a shaft
        # towards the toe and leaving a gap when the leg folds.
        points[-1] = paw_target - rotation @ rest_axis * 3
        fractions[-1] = (length - 3) / length
        if (np.linalg.norm(points[-1]-points[-2]) < 2 or
                (points[-2]-points[-3]) @ (points[-1]-points[-2]) < 0):
            points = np.delete(points, -2, axis=0)
            fractions = np.delete(fractions, -2)
        pad = 12
        lo = np.maximum(0, np.floor(points.min(axis=0) - pad)).astype(int)
        hi = np.minimum(self.size, np.ceil(points.max(axis=0) + pad)).astype(int)
        yy, xx = np.mgrid[lo[1]:hi[1], lo[0]:hi[0]]
        grid = np.stack((xx, yy), axis=-1).astype(float)
        vectors = np.diff(points, axis=0)
        lengths = np.linalg.norm(vectors, axis=1)
        assert np.all(lengths > .01), 'Review collapsed leg joint'
        directions = vectors / lengths[:, None]
        normals = np.stack([normal(v) for v in directions])
        joined = [normals[0]]
        for before, after in zip(normals, normals[1:]):
            middle = before + after
            size = np.linalg.norm(middle)
            assert size > .05, ('Review folded-back leg joint',name,points.tolist())
            middle /= size
            joined.append(middle * min(1.3, 1 / max(.1, middle @ after)))
        joined.append(normals[-1])
        # A bounded triangle mesh avoids extrapolating end pixels beyond a bend.
        positions = np.concatenate(([points[0]-3*directions[0]], points, [points[-1]+5*directions[-1]]))
        joined = np.array([joined[0], *joined, joined[-1]])
        along = np.concatenate(([-3], fractions * length, [length+5]))
        source_centers = root + along[:, None] * rest_axis
        # Nine-pixel half-width is only a sampling envelope; transparent source
        # pixels determine the silhouette, rather than an invented limb width.
        dst = np.stack((positions-9*joined, positions+9*joined), axis=1)
        src = np.stack((source_centers-9*normal(rest_axis), source_centers+9*normal(rest_axis)), axis=1)
        patch = np.zeros((*xx.shape, 4), dtype=np.uint8)
        for i in range(len(positions)-1):
            for corners in [[(i,0),(i,1),(i+1,0)],[(i,1),(i+1,1),(i+1,0)]]:
                target = np.array([dst[row,side] for row,side in corners])
                source = np.array([src[row,side] for row,side in corners])
                matrix = np.column_stack((target[1]-target[0],target[2]-target[0]))
                if abs(np.linalg.det(matrix)) < .01:
                    continue
                uv = (grid-target[0]) @ np.linalg.inv(matrix).T
                inside = (uv[...,0] >= -1e-7) & (uv[...,1] >= -1e-7) & (uv.sum(axis=-1) <= 1+1e-7)
                coords = source[0] + uv[...,0,None]*(source[1]-source[0]) + uv[...,1,None]*(source[2]-source[0])
                xy = np.rint(coords).astype(int)
                xy[...,0] = np.clip(xy[...,0],0,self.size[0]-1)
                xy[...,1] = np.clip(xy[...,1],0,self.size[1]-1)
                sampled = shaft[xy[...,1],xy[...,0]]
                visible = inside & (sampled[...,3] > 0)
                patch[visible] = sampled[visible]
        # A rigid source paw overlays the deformed shaft at the reviewed target.
        xy = np.rint((grid - paw_target) @ rotation + rest_paw).astype(int)
        valid = (xy[..., 0] >= 0) & (xy[..., 0] < self.size[0]) & (xy[..., 1] >= 0) & (xy[..., 1] < self.size[1])
        xy[..., 0] = np.clip(xy[..., 0], 0, self.size[0]-1)
        xy[..., 1] = np.clip(xy[..., 1], 0, self.size[1]-1)
        paw = toes[xy[..., 1], xy[..., 0]]
        visible = valid & (paw[..., 3] > 0)
        patch[visible] = paw[visible]
        out = Image.new('RGBA', self.size)
        out.paste(Image.fromarray(patch), tuple(lo))
        return out


def sample_cycle(keys, phase):
    """Interpolate authored poses across the loop, including last -> first."""
    position = (phase % 1) * len(keys)
    i = math.floor(position); weight = position - i
    def mix(a, b):
        if isinstance(a, dict):
            return {key: mix(a[key], b[key]) for key in a}
        if isinstance(a, list):
            return [mix(x, y) for x, y in zip(a, b)]
        return a * (1-weight) + b * weight
    return mix(keys[i], keys[(i+1) % len(keys)])
