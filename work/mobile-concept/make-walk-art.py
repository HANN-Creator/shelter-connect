from PIL import Image,ImageDraw
from pathlib import Path
import random
b=Path(__file__).parent
rng=random.Random(27)
im=Image.new('RGB',(448,448),'#9bcfc5');d=ImageDraw.Draw(im)
for y in range(3,448,9):
    for x in range(rng.randrange(10),448,22):d.line((x,y,x+5,y),fill='#b6e0ce')
land=[(34,22),(401,22),(401,30),(419,30),(419,45),(430,45),(430,395),(414,395),(414,419),(398,419),(398,430),(46,430),(46,419),(26,419),(26,400),(18,400),(18,49),(26,49),(26,31),(34,31)]
d.polygon([(x+3,y+5) for x,y in land],fill='#739f8a');d.polygon(land,fill='#c4d693');d.line(land+[land[0]],fill='#93b27d',width=3)
for _ in range(1250):
    x,y=rng.randrange(32,414),rng.randrange(32,414)
    col=rng.choice(['#bfd18c','#d1df9e','#b5cd83','#d4dfa6'])
    d.rectangle((x,y,x+2,y+1),fill=col)
    if rng.random()<.3:d.point((x+1,y-1),fill=col)
# Walking paths.
for box in [(70,215,374,257),(192,160,239,409),(134,250,193,290),(231,279,319,313)]:d.rounded_rectangle(box,radius=12,fill='#e4cf9d')
for _ in range(170):
    x,y=rng.randrange(58,390),rng.randrange(166,414)
    if im.getpixel((x,y))==(228,207,157):d.rectangle((x,y,x+2,y+1),fill=rng.choice(['#d8be90','#efdbb0']))
for y in range(180,405,42):d.rounded_rectangle((207,y,224,y+7),radius=2,fill='#f0dfbb')

def fence(x,y,n):
    for i in range(n):
        xx=x+i*21;d.rectangle((xx,y+5,xx+22,y+9),fill='#947454');d.rectangle((xx,y+1,xx+22,y+5),fill='#dbc597')
        d.rectangle((xx,y-3,xx+5,y+15),fill='#95774e');d.rectangle((xx+1,y-4,xx+4,y+11),fill='#d9b887');d.line((xx+1,y-3,xx+4,y-3),fill='#f0d5a4')
fence(33,394,7);fence(263,395,7);fence(250,49,7)

def tree(x,y,size=1):
    w=int(38*size);h=int(47*size)
    d.ellipse((x-w//2+3,y-8,x+w//2+5,y+3),fill='#a8bf7c')
    d.rectangle((x-4,y-h//2,x+4,y),fill='#856d50');d.rectangle((x-2,y-h//2,x+1,y-2),fill='#b49563')
    cols=['#789961','#97b77a','#a9c88a']
    d.rounded_rectangle((x-w//2,y-h,x+w//2,y-11),radius=10,fill=cols[0])
    d.rounded_rectangle((x-w//2+2,y-h+1,x+w//2-2,y-15),radius=10,fill=cols[1])
    d.rectangle((x-w//2+8,y-h+4,x+3,y-h+8),fill=cols[2]);d.rectangle((x-w//2+5,y-h+8,x-w//2+9,y-h+18),fill=cols[2])
    for i in range(9):
        xx=x+rng.randint(-w//2+5,w//2-6);yy=y-rng.randint(18,h-5);d.rectangle((xx,yy,xx+3,yy+2),fill=rng.choice(cols))

def bush(x,y):
    d.rounded_rectangle((x-9,y-9,x+9,y+2),radius=5,fill='#879f67');d.rounded_rectangle((x-8,y-10,x+7,y-1),radius=5,fill='#a9c27c');d.rectangle((x-5,y-7,x-2,y-5),fill='#cddd96')
def flower(x,y,col):
    d.line((x,y,x,y+6),fill='#849b62');d.rectangle((x-3,y-1,x+3,y+1),fill=col);d.rectangle((x-1,y-3,x+1,y+3),fill=col);d.point((x,y),fill='#e2b36a')
for x,y,sc in [(65,103,1.1),(78,194,.9),(294,112,1.05),(379,164,1.05),(386,296,.95),(73,359,1.1),(335,396,.9),(116,401,.8)]:tree(x,y,sc)
for x,y in [(102,70),(315,171),(358,219),(52,270),(301,372),(132,335),(383,368),(269,399)]:bush(x,y)
for _ in range(80):
    x,y=rng.randrange(42,405),rng.randrange(40,410)
    if im.getpixel((x,y)) in [(196,214,147),(191,209,140),(209,223,158)]:flower(x,y,rng.choice(['#f5e6a1','#f0c4b0','#fff4c9']))
# Flower bed (blocking rectangle 76,278,116,35).
d.rounded_rectangle((76,285,121,317),radius=4,fill='#a99566');d.rectangle((78,286,119,313),fill='#c8b182')
for y in range(288,312,10):
    for x in range(84,119,11):flower(x,y,rng.choice(['#f4b3a1','#fff0b8','#e7c0d5']))
# Small pond, stepping stones and reeds.
d.rounded_rectangle((302,320,375,365),radius=13,fill='#91b089');d.rounded_rectangle((305,321,372,361),radius=12,fill='#90c8bf');d.rounded_rectangle((312,325,366,353),radius=10,fill='#acd8c2')
for x,y in [(319,332),(346,342),(320,350)]:d.line((x,y,x+8,y),fill='#d7e9cb')
for x,y in [(307,318),(369,342),(331,365)]:
    d.rounded_rectangle((x,y,x+10,y+6),radius=3,fill='#819b8c');d.line((x+3,y+1,x+8,y+1),fill='#c6d4b8')
for x in range(371,380,3):d.line((x,359,x-2,348),fill='#789b69')
# Main cottage. Collision includes its whole roof and footprint.
d.rectangle((122,111,225,174),fill='#8e7756');d.rectangle((126,110,221,170),fill='#e4c89b');d.rectangle((126,143,221,146),fill='#c6a977')
d.rectangle((143,131,159,148),fill='#977e5e');d.rectangle((145,133,157,146),fill='#94bba9');d.line((151,132,151,148),fill='#f2dfb0');d.line((144,140,158,140),fill='#f2dfb0')
d.rectangle((190,131,208,148),fill='#977e5e');d.rectangle((192,133,206,146),fill='#94bba9');d.line((199,132,199,148),fill='#f2dfb0');d.line((191,140,207,140),fill='#f2dfb0')
d.rectangle((166,136,184,174),fill='#806a56');d.rectangle((169,138,181,171),fill='#b89573');d.point((178,154),fill='#f7e2ad')
d.rectangle((165,175,186,178),fill='#b99d72');d.rectangle((162,179,189,183),fill='#d8c198');d.rectangle((160,184,191,188),fill='#eedbbb')
roof=[(115,119),(115,111),(121,111),(121,104),(128,104),(128,97),(135,97),(135,90),(142,90),(142,83),(207,83),(207,90),(214,90),(214,98),(221,98),(221,105),(228,105),(228,113),(233,113),(233,120)]
d.polygon([(x+2,y+3) for x,y in roof],fill='#91726b');d.polygon(roof,fill='#ba8e82')
for y in [88,96,104,112]:d.line((143-(y-88),y,205+(y-88),y),fill='#d2aa96',width=2)
for x in range(141,213,12):d.line((x,84,x-8,116),fill='#a27f79')
d.rectangle((183,72,195,94),fill='#987e75');d.rectangle((181,71,197,75),fill='#b6a08d')
d.rectangle((121,119,228,123),fill='#d9b99a')
# A kennel and its water dish.
d.rectangle((317,174,353,201),fill='#a28361');d.rectangle((320,174,350,199),fill='#e3c295');d.polygon([(312,177),(334,153),(358,177)],fill='#9a8eac');d.line((314,177,334,156,356,177),fill='#b4a8c3',width=2)
d.rounded_rectangle((328,180,342,201),radius=6,fill='#816e5d');d.rectangle((327,193,343,202),fill='#816e5d')
d.ellipse((361,202,373,207),fill='#769d9d');d.ellipse((363,201,371,204),fill='#bcdcd5')
# Bench near the path.
d.rectangle((272,286,306,290),fill='#9b7d58');d.rectangle((272,281,306,285),fill='#d5b88a');d.rectangle((276,285,280,299),fill='#9d805b');d.rectangle((299,285,303,299),fill='#9d805b');d.rectangle((272,291,307,294),fill='#dfc599')
im.save(b/'garden-world.png')
# Four facing directions, two walking frames, 20x28 each.
sheet=Image.new('RGBA',(160,28))
for facing in range(4):
 for step in range(2):
    p=Image.new('RGBA',(20,28));z=ImageDraw.Draw(p);left=5+(step==1);right=12-(step==1)
    z.rectangle((left,23,left+3,26),fill='#4c5362');z.rectangle((right,23,right+3,26),fill='#4c5362')
    z.rectangle((5,14,15,22),fill='#d89588');z.rectangle((7,14,13,23),fill='#edb39b');z.rectangle((3,16,5,21),fill='#e4b990');z.rectangle((15,16,17,21),fill='#e4b990')
    z.rectangle((7,11,13,16),fill='#e9c6a1');z.rectangle((4,4,16,11),fill='#6c605b');z.rectangle((6,6,15,12),fill='#f0cda7')
    z.rectangle((5,2,15,5),fill='#769888');z.rectangle((4,4,17,7),fill='#95b4a0');z.rectangle((3,6,18,8),fill='#648879');z.rectangle((6,3,10,4),fill='#c1d0ac')
    if facing==0:
        z.point((8,10),fill='#545360');z.point((13,10),fill='#545360');z.rectangle((8,16,12,18),fill='#f2d58d')
    elif facing==1:
        z.rectangle((5,8,15,12),fill='#746259');z.rectangle((7,15,14,21),fill='#d3b46f');z.rectangle((8,16,13,19),fill='#edcd83')
    else:
        z.rectangle((6,8,10,12),fill='#73635a');z.point((14,10),fill='#55515a');z.rectangle((5,15,8,21),fill='#dfc780')
        if facing==2:p=p.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    sheet.alpha_composite(p,((facing*2+step)*20,0))
sheet.save(b/'walker-strip.png')
print('Garden and walking character drawn.')
