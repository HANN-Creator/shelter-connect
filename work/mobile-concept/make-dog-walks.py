from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
import math

base = Path(__file__).parent
K = '#182136'
CREAM = '#fff5e2'
palettes = [
    ('bori', '#343d4c', '#526074', '#263142', '#526074'),
    ('dubu', '#bd8857', '#d7a875', '#876046', CREAM),
    ('bami', '#343d4c', '#526074', '#263142', '#afb6bc'),
]

def sprite(which, direction, frame, dip=0):
    name, coat, light, shade, muzzle = palettes[which]
    im = Image.new('RGBA', (36, 36))
    p = ImageDraw.Draw(im)
    phase = frame / 8 * math.tau
    def poly(points, color): p.polygon(points, fill=color)
    def rect(box, color): p.rectangle(box, fill=color)
    if direction == 1:  # Right; left uses this atlas mirrored.
        # Four feet alternate diagonal pairs. The torso stays at a fixed height.
        for x, offset, far in [(12, 0, True), (24, math.pi, True), (9, math.pi, False), (22, 0, False)]:
            angle = phase + offset
            step = round(math.cos(angle) * 3)
            lift = max(0, round(math.sin(angle) * 2))
            poly([(x-1, 23), (x+2, 23), (x+step+2, 31-lift), (x+step-1, 31-lift)], K)
            poly([(x, 24), (x+1, 24), (x+step+1, 30-lift), (x+step, 30-lift)], shade if far else coat)
            if not far: rect((x+step-1, 30-lift, x+step+2, 31-lift), CREAM if which == 1 else light)
        # Tail and rib cage.
        tail = round(math.sin(phase*.5))
        poly([(9, 21), (5, 20), (3, 16+tail), (2, 13+tail), (4, 13+tail), (6, 17+tail), (10, 18)], K)
        poly([(8, 20), (5, 18), (4, 14+tail), (5, 15+tail), (7, 18), (10, 19)], coat)
        if which == 1: rect((3, 13+tail, 4, 15+tail), CREAM)
        poly([(8, 16), (23, 16), (26, 20), (25, 26), (9, 26), (6, 23), (6, 19)], K)
        poly([(9, 17), (22, 17), (25, 20), (24, 25), (10, 25), (7, 22), (7, 20)], coat)
        rect((10, 18, 20, 19), light)
        if which == 1: poly([(22, 19), (25, 21), (24, 25), (18, 25), (19, 22)], CREAM)
        elif which == 0: poly([(22, 20), (24, 20), (24, 25), (21, 25)], CREAM)
        body = im.copy();im = Image.new('RGBA', (36,36));p=ImageDraw.Draw(im)
        if which == 0:
            poly([(17, 10), (17, 5), (20, 5), (23, 8), (27, 8), (30, 11), (30, 15), (33, 15), (33, 19), (29, 21), (21, 20), (17, 17)], K)
            poly([(18, 10), (18, 7), (20, 8), (23, 9), (27, 9), (29, 12), (29, 16), (31, 16), (31, 19), (28, 20), (22, 19), (18, 16)], coat)
            rect((18, 8, 19, 10), '#8b6574')
        else:
            poly([(20, 7), (27, 7), (30, 10), (30, 15), (33, 16), (33, 19), (29, 21), (20, 20), (17, 16), (17, 10)], K)
            poly([(20, 8), (26, 8), (29, 11), (29, 16), (31, 17), (31, 19), (28, 20), (21, 19), (18, 15), (18, 11)], coat)
            poly([(18, 9), (21, 10), (22, 15), (20, 18), (17, 17), (16, 13), (16, 10)], K)
            poly([(18, 10), (20, 11), (20, 15), (19, 17), (18, 16), (17, 13)], shade)
            if which == 1: poly([(25, 8), (27, 9), (28, 11), (26, 14), (24, 14)], CREAM)
        poly([(27, 15), (31, 16), (31, 19), (27, 19), (25, 17)], muzzle)
        rect((31, 15, 33, 16), K)
        rect((27, 11, 28, 13), K); rect((27, 11, 27, 11), CREAM)
        rect((28, 18, 30, 18), K)
        if which == 2:
            poly([(20, 19), (25, 20), (25, 22), (20, 21)], '#f28aa1')
            rect((23, 22, 24, 23), '#ffdc79')
        body.alpha_composite(im,(0,dip));im=body
    else:
        # Front/rear share a planted four-legged body and the original identity.
        for j, x in enumerate([12, 23]):
            lift=max(0, round(math.sin(phase+j*math.pi)*2))
            rect((x-1, 23, x+2, 31-lift), K)
            rect((x, 24, x+1, 30-lift), coat)
            rect((x-1, 30-lift, x+2, 31-lift), CREAM if which==1 else light)
        poly([(12, 17), (24, 17), (25, 24), (23, 28), (12, 28), (10, 24)], K)
        poly([(13, 18), (23, 18), (24, 24), (22, 27), (13, 27), (11, 24)], coat)
        if direction == 0:
            poly([(15, 19), (21, 19), (21, 23), (19, 26), (16, 26), (14, 23)], CREAM)
        original=Image.open(base/(name+'-avatar.png')).convert('RGBA')
        head=original.crop((0,0,32,20))
        if which==1:
            ImageDraw.Draw(head).rectangle((28,16,31,19),fill=(0,0,0,0))
        if direction==2:
            # Rear silhouette retains ears, without front eyes or chest markings.
            inside=head.getchannel('A').filter(ImageFilter.MinFilter(3));pixels=head.load()
            for yy in range(head.height):
                for xx in range(head.width):
                    value=pixels[xx,yy]
                    if value[3]:
                        pixels[xx,yy]=tuple(bytes.fromhex((coat if inside.getpixel((xx,yy)) else K)[1:]))+(255,)
            h=ImageDraw.Draw(head);h.rectangle((13,9,19,10),fill=light)
        im.alpha_composite(head,(2,0))
        p=ImageDraw.Draw(im)
        if direction==2:
            poly([(16, 24), (18, 23), (20, 26), (20, 29), (18, 29), (17, 26)], K)
            rect((18,25,19,28), coat)
    return im

atlas=Image.new('RGBA',(8*36,12*36))
for dog in range(3):
    for direction in range(3):
        for frame in range(8):
            atlas.alpha_composite(sprite(dog,direction,frame),(frame*36,(dog*3+direction)*36))
    for frame in range(8):
        atlas.alpha_composite(sprite(dog,1,0,dip=frame),(frame*36,(9+dog)*36))
atlas.save(base/'dog-walk-atlas.png')
preview=Image.new('RGBA',atlas.size,'#c4d693');preview.alpha_composite(atlas)
preview.convert('RGB').resize((576,864),Image.Resampling.NEAREST).save(base/'dog-walk-preview.png')
print('Prepared three dog identities, three views and eight walking poses each.')
