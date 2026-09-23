"""Render reviewed locomotion templates against an explicit character profile.
No network calls; original photos and API credentials are not required.
"""
from pathlib import Path
import argparse,json,math
import numpy as np
from PIL import Image,ImageDraw
from outline import restore_outline

HERE=Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--profile',default=str(HERE/'dubu.profile.json'))
parser.add_argument('--action',choices=['WALK','RUN','BACK_OFF'],required=True)
parser.add_argument('--out',required=True)
args=parser.parse_args()
profile_path=Path(args.profile).resolve()
PROFILE=json.loads(profile_path.read_text())
TEMPLATES=json.loads((HERE/'motion-templates.json').read_text())
BASE=Image.open(profile_path.parent/PROFILE['baseImage']).convert('RGBA')
assert BASE.size==(64,64),'Only 64px profiles are supported in this prototype'
# Motion templates own geometry; this character's base image owns every color.
PALETTE={name:BASE.getpixel(tuple(xy)) for name,xy in PROFILE['paletteSamples'].items()}
assert all(color[3]==255 for color in PALETTE.values()),'Palette samples must be inside the character'
LINE,COAT,SHADE,FAR,TOE,FAR_TOE=[PALETTE[k] for k in ['LINE','COAT','SHADE','FAR','TOE','FAR_TOE']]
mask=Image.new('L',(64,64));ImageDraw.Draw(mask).polygon([tuple(p) for p in PROFILE['bodyMask']],fill=255)
BODY=BASE.copy();BODY.putalpha(Image.fromarray(np.minimum(np.array(mask),np.array(BASE.getchannel('A')))))

def ik(root, target, l1, l2, bend):
    a=np.array(root,float); c=np.array(target,float); v=c-a
    dist=float(np.linalg.norm(v)); u=v/dist
    if dist>l1+l2-.01:
        raise ValueError(('unreachable joint',root,target,dist,l1+l2))
    along=(l1*l1-l2*l2+dist*dist)/(2*dist)
    height=math.sqrt(max(0,l1*l1-along*along))
    return tuple(a+along*u+bend*height*np.array([-u[1],u[0]]))

def segment(d,a,b,wa,wb,color):
    a=np.array(a);b=np.array(b);v=b-a;n=np.array([-v[1],v[0]])/np.linalg.norm(v)
    d.polygon([tuple(np.rint(p).astype(int)) for p in [a+n*wa/2,b+n*wb/2,b-n*wb/2,a-n*wa/2]],fill=color)

def front_limb(root,joint,foot,near):
    # Keep the reviewed geometry; sample the coat from the current character.
    im=Image.new('RGBA',(64,64));d=ImageDraw.Draw(im)
    color=COAT if near else FAR;wide=7 if near else 5;narrow=5 if near else 4
    segment(d,root,joint,wide+2,narrow+2,LINE);segment(d,joint,foot,narrow+2,narrow+1,LINE)
    x,y=joint;d.ellipse((round(x)-3,round(y)-3,round(x)+3,round(y)+3),fill=LINE)
    segment(d,root,joint,wide,narrow,color);segment(d,joint,foot,narrow,narrow-1,color)
    d.ellipse((round(x)-2,round(y)-2,round(x)+2,round(y)+2),fill=color)
    x,y=map(round,foot)
    d.polygon([(x-2,y-2),(x+1,y-2),(x+3,y),(x+3,y+2),(x-2,y+2)],fill=LINE)
    d.polygon([(x-1,y-1),(x+1,y-1),(x+2,y),(x+2,y+1),(x-1,y+1)],fill=TOE if near else FAR_TOE)
    if near:d.point((x,y+1),fill=SHADE);d.point((x+2,y+1),fill=SHADE)
    return im

def solid_component(im):
    arr=np.array(im);alpha=arr[:,:,3]>0;seen=set();components=[]
    for y,x in zip(*np.where(alpha)):
        if (x,y) in seen:continue
        stack=[(x,y)];seen.add((x,y));part=[]
        while stack:
            xx,yy=stack.pop();part.append((xx,yy))
            for nx in range(max(0,xx-1),min(64,xx+2)):
                for ny in range(max(0,yy-1),min(64,yy+2)):
                    if alpha[ny,nx] and (nx,ny) not in seen:seen.add((nx,ny));stack.append((nx,ny))
        components.append(part)
    keep=max(components,key=len);out=np.zeros_like(arr)
    for x,y in keep:out[y,x]=arr[y,x]
    return Image.fromarray(out)

def cut(poly):
    m=Image.new('L',(64,64));ImageDraw.Draw(m).polygon(poly,fill=255)
    im=BODY.copy();im.putalpha(Image.fromarray(np.minimum(np.array(m),np.array(BODY.getchannel('A')))))
    return solid_component(im)

def affine(pivot, dest, angle=0, sx=1, sy=1):
    a=math.radians(angle);c=math.cos(a);s=math.sin(a)
    matrix=np.array([[c*sx,-s*sy],[s*sx,c*sy]])
    offset=np.array(dest)-matrix@np.array(pivot)
    return matrix,offset

def point(xy,xf):return tuple(xf[0]@np.array(xy)+xf[1])

def warp(im,xf):
    matrix,off=xf;inv=np.linalg.inv(matrix);shift=-inv@off
    return solid_component(im.transform((64,64),Image.Transform.AFFINE,(*inv[0],shift[0],*inv[1],shift[1]),resample=Image.Resampling.NEAREST))

def redraw_hind(g,root_delta,near):
    # Preserve ground-space paw paths. Only the upper joints follow the moving
    # pelvis; rotating the finished sprite would make planted feet skate.
    points=[]
    for key,weight in [('hip',1),('stifle',.65),('hock',.15),('paw',0)]:
        points.append(tuple(np.array(g[key])+np.array(root_delta)*weight))
    widths=[8,5,3,3] if near else [6,4,2.5,2.5]
    color=COAT if near else FAR
    im=Image.new('RGBA',(64,64));d=ImageDraw.Draw(im)
    for extra,fill in [(2,LINE),(0,color)]:
        for j in range(3):segment(d,points[j],points[j+1],widths[j]+extra,widths[j+1]+extra,fill)
        for (x,y),w in zip(points[:-1],widths[:-1]):
            r=(w+extra)/2;d.ellipse((round(x-r),round(y-r),round(x+r),round(y+r)),fill=fill)
    paw=points[3];a=math.radians(g['pawAngle']);c=math.cos(a);s=math.sin(a)
    def poly(coords,color):
        d.polygon([(round(paw[0]+x*c-y*s),round(paw[1]+x*s+y*c)) for x,y in coords],fill=color)
    poly([(-2,-2),(1,-2),(3,0),(3,2),(-2,2)],LINE)
    poly([(-1,-1),(1,-1),(2,0),(2,1),(-1,1)],TOE if near else FAR_TOE)
    return im,{**g,**dict(zip(['hip','stifle','hock','paw'],points))}

TORSO,HEAD,TAIL=[cut([tuple(p) for p in PROFILE['parts'][n]]) for n in ['torso','head','tail']]
ACTION=args.action;config=TEMPLATES['actions'][ACTION]
OUT=Path(args.out);FRAMES=OUT/'frames';FRAMES.mkdir(parents=True,exist_ok=True)
LEGS=PROFILE['legs'];pivot=np.array(PROFILE['torsoPivot'])

def ground_path(t,leg):
    phase=(t-leg['offset'])%1;swing=config['swingFraction'];stride=config['stride']
    if phase<swing:
        q=phase/swing;smooth=q*q*(3-2*q)
        clearance=config.get('hindSwingLiftPixels',2.5) if leg['hind'] else 2.5
        x=stride/2-stride*smooth;lift=clearance*math.sin(math.pi*q)**2;planted=False
    else:
        q=(phase-swing)/(1-swing);x=-stride/2+stride*q;lift=0;planted=True
    if config['direction']==1:x=-x
    return (leg['paw'][0]+x,leg['paw'][1]-lift),x,lift,planted

def walking_hind_pose(root,foot,t,leg):
    """Extend under load, flex during clearance, then extend into contact.

    Build joints from the moving hip and the planted foot together. A fixed
    knee/hock shape would stay folded throughout the support phase.
    """
    phase=(t-leg['offset'])%1
    q=phase/config['swingFraction'] if phase<config['swingFraction'] else None
    flex=math.sin(math.pi*q)**2 if q is not None else 0
    root=np.array(root,float);foot=np.array(foot,float);axis=foot-root
    normal=np.array([axis[1],-axis[0]])/np.linalg.norm(axis)
    pose=config['hindPose']
    knee_bend=pose['supportKneeOffset']+(pose['swingKneeOffset']-pose['supportKneeOffset'])*flex
    hock_bend=pose['supportHockOffset']+(pose['swingHockOffset']-pose['supportHockOffset'])*flex
    knee=root+pose['kneeFraction']*axis+normal*knee_bend
    hock=root+pose['hockFraction']*axis+normal*hock_bend
    return {'hip':tuple(root),'stifle':tuple(knee),'hock':tuple(hock),
            'paw':tuple(foot),'pawAngle':0,'swingProgress':q,'flexAmount':flex}

def run_map(xy,name,leg):
    source_name=('nearHind' if leg['near'] else 'farHind') if leg['hind'] else name
    origin=np.array(config['sourceRestHips'][source_name],float)
    axis=np.array(config['sourceRestPaws'][name],float)-origin
    target=np.array(leg['paw'],float)-np.array(leg['root'],float)
    source_basis=np.column_stack((axis,[-axis[1],axis[0]]))
    target_basis=np.column_stack((target,[-target[1],target[0]]))
    return tuple(np.array(leg['root'])+target_basis@np.linalg.solve(source_basis,np.array(xy)-origin))

frames=[];guides=[]
for i in range(config['frameCount']):
    t=i/config['frameCount'];a=2*math.pi*t
    if ACTION=='RUN':
        k=i//2;f=(i%2)/2;keys=config['bodyKeyPoses']
        sx,sy,pitch,dx,dy,head_pitch,tail_swing=[x*(1-f)+y*f for x,y in zip(keys[k],keys[(k+1)%len(keys)])]
    else:
        dx=-.8-.65*math.sin(a-.8);dy=.8+.4*math.cos(2*a)
        pitch=-1.5+1.8*math.sin(a);sx=.98;sy=1
        head_pitch=-.8-.8*math.sin(a-.7);tail_swing=4+2*math.sin(a-.5)
    torsoxf=affine(pivot,pivot+[dx,dy],pitch,sx,sy)
    neck=point(PROFILE['neck'],torsoxf)
    headxf=affine(PROFILE['neck'],neck,head_pitch)
    tailxf=affine(PROFILE['tailBase'],point(PROFILE['tailBase'],torsoxf),pitch+tail_swing)
    parts={};joints={}
    for name,leg in LEGS.items():
        root=point(leg['root'],torsoxf)
        if ACTION=='RUN':
            if leg['hind']:
                side='nearHind' if leg['near'] else 'farHind'
                old=config['hindPoses'][i][side]
                g={**old,**{k:run_map(old[k],name,leg) for k in ['hip','stifle','hock','paw']}}
                parts[name],joints[name]=redraw_hind(g,np.array(root)-np.array(g['hip']),leg['near'])
                continue
            foot=run_map(config['frontPaws'][i][name],name,leg)
            lens=(10.5,11) if leg['near'] else (9,10)
            planted=False
        else:
            foot,x,lift,planted=ground_path(t,leg)
            if leg['hind']:
                if ACTION=='WALK':
                    g=walking_hind_pose(root,foot,t,leg)
                    parts[name],joints[name]=redraw_hind(g,(0,0),leg['near'])
                else:
                    px,py=leg['paw'];knee=(leg['root'][0]+2+.2*x,leg['root'][1]+7-.25*lift)
                    hock=(px-2+.8*x,py-5-.7*lift)
                    g={'hip':leg['root'],'stifle':knee,'hock':hock,'paw':foot,'pawAngle':0}
                    parts[name],joints[name]=redraw_hind(g,np.array(root)-np.array(leg['root']),leg['near'])
                joints[name]['planted']=planted
                continue
            lens=(9.1,9.25) if leg['near'] else (7,7.5)
        length=math.dist(root,foot)
        if length>sum(lens)-.01:lens=tuple(v*length/(sum(lens)-.01) for v in lens)
        elbow=ik(root,foot,*lens,1)
        parts[name]=front_limb(root,elbow,foot,leg['near'])
        joints[name]={'shoulder':root,'elbow':elbow,'paw':foot,'planted':planted}
    im=Image.new('RGBA',(64,64))
    for part in [parts['FH'],parts['FF'],warp(TAIL,tailxf),parts['NH'],parts['NF'],warp(TORSO,torsoxf),warp(HEAD,headxf)]:im.alpha_composite(part)
    # Tiny isolated raster fragments from cutout rotation are not anatomy.
    im=restore_outline(solid_component(im),LINE)
    im.save(FRAMES/f'{i+1:02d}.png');frames.append(im)
    guides.append({'phase':t,'bodyOffset':[dx,dy],'bodyPitch':pitch,'headAnchor':neck,'legs':joints})

sheet=Image.new('RGB',(6*272,4*288),'#f7f2e5');d=ImageDraw.Draw(sheet)
for i,im in enumerate(frames):
    x=i%6*272;y=i//6*288;big=im.resize((256,256),Image.Resampling.NEAREST)
    sheet.paste(big,(x+8,y+24),big);d.text((x+8,y+4),f'{ACTION} {i+1}',fill='#344936')
sheet.save(OUT/'contact-sheet.png')
report={'method':'deterministic motion-template harness','profile':PROFILE['id'],'templateVersion':TEMPLATES['version'],'action':ACTION,'frameCount':len(frames),'frameDurationMs':config['durationMs'],'anchor':PROFILE['anchor'],'frames':guides}
if ACTION=='WALK':report['hindGait']='extend-on-support-flex-on-swing'
if ACTION!='RUN':report['suggestedWorldVelocityPixelsPerSecond']=config['direction']*config['stride']/((1-config['swingFraction'])*len(frames)*config['durationMs']/1000)
(OUT/'rig.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'action':ACTION,'frames':len(frames),'profile':PROFILE['id']}))
