from pathlib import Path
from PIL import Image, ImageDraw

# Original pixel scenery for the conversation screen, drawn on an integer grid.
im = Image.new('RGB', (256, 133), '#81c7ed')
d = ImageDraw.Draw(im)
def box(x, y, w, h, color):
    d.rectangle((x, y, x+w-1, y+h-1), fill=color)
def cloud(x, y, scale=1):
    for a,b,w,h in [(4,4,32,8),(10,0,15,4),(0,8,40,5)]:
        box(x+a*scale, y+b*scale, w*scale, h*scale, '#fff8dd')
cloud(11,27); cloud(168,20); cloud(218,42)
for x,y,w,h in [(0,75,25,28),(13,65,20,38),(31,78,29,25),(58,86,31,17),(167,84,37,19),(194,71,26,32),(218,80,38,23)]:
    box(x,y,w,h,'#b5dfca')
for x in (27,223):
    box(x-3,76,7,30,'#b4895f'); box(x+1,78,3,27,'#91704f')
    for a,b,w,h in [(-15,57,29,18),(-11,48,22,11),(-18,62,36,10),(-10,73,23,8)]:
        box(x+a,b,w,h,'#82ac6d')
    box(x-10,54,15,4,'#a4c784'); box(x-14,59,6,6,'#a4c784')
box(0,101,256,32,'#95b66b')
box(0,101,256,4,'#c1d98b')
box(0,116,256,17,'#a28c69')
for x in range(0,256,19):
    box(x,114,11,5,'#95b66b')
for x,y in [(10,108),(60,112),(187,109),(239,107),(75,104)]:
    box(x,y,4,2,'#cee4a3');box(x+2,y-2,2,2,'#cee4a3')
for x,y in [(47,104),(205,108)]:
    box(x,y,2,5,'#6e955e');box(x-2,y,6,2,'#fff2a5');box(x,y-2,2,6,'#fff2a5');box(x,y,2,2,'#e7b466')
for x,y in [(11,124),(39,129),(76,121),(165,127),(219,123),(249,129)]:
    box(x,y,4,2,'#bda17a')
im.save(Path(__file__).with_name('pet-chat-room.png'))
