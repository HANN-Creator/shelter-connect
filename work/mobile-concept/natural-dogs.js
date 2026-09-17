 function initNaturalDog(d){
  d.vx=0;d.vy=0;d.heading=d.kind==='rest'?0:1;d.turnTimer=0;d.turnTo=null;
  d.phase=d.kind==='play'?'fetch':'sniff';d.hold=d.kind==='sniff'?2.5:0;d.route=0;
  d.target=null;d.greetTarget=null;d.headDip=0;d.alert=0;d.lookX=0;d.labelY=d.y-40;
  d.carrying=false;d.ballX=d.homeX+23;d.ballY=d.homeY-7;d.ballGroundY=d.ballY;
  d.blockedTime=0;d.pauseTime=0;
  return d;
 }
 function naturalHeading(dx,dy,current){
  if(Math.abs(dx)>Math.abs(dy)*1.2)return dx<0?3:1;
  if(Math.abs(dy)>Math.abs(dx)*1.2)return dy<0?2:0;
  return current;
 }
 function moveDog(d,tx,ty,speed,dt){
  let dx=tx-d.x,dy=ty-d.y,distance=Math.hypot(dx,dy);const oldX=d.x,oldY=d.y;
  let desiredX=0,desiredY=0;const accel=d.kind==='sniff'?18:48;
  if(d.turnTimer>0){
   d.turnTimer=Math.max(0,d.turnTimer-dt);
   if(!d.turnTimer){d.heading=d.turnTo;d.turnTo=null;}
  }else if(distance>.55&&speed>0){
   const heading=distance>3?naturalHeading(dx,dy,d.heading):d.heading,opposite=(heading+2)%4===d.heading;
   if(opposite&&Math.hypot(d.vx,d.vy)<1&&d.headDip<.6){
    d.turnTo=heading;d.turnTimer=.24;d.heading=(d.heading===1||d.heading===3)?0:1;
   }else if(!opposite){
    d.heading=heading;
    const arriving=Math.min(speed,distance*3,Math.sqrt(2*accel*distance));
    desiredX=dx/distance*arriving;desiredY=dy/distance*arriving;
   }
  }
  const dvx=desiredX-d.vx,dvy=desiredY-d.vy,dv=Math.hypot(dvx,dvy),limit=accel*dt;
  if(dv>limit){d.vx+=dvx/dv*limit;d.vy+=dvy/dv*limit;}else{d.vx=desiredX;d.vy=desiredY;}
  if(canDogStand(d,d.x+d.vx*dt,d.y))d.x+=d.vx*dt;else d.vx=0;
  if(canDogStand(d,d.x,d.y+d.vy*dt))d.y+=d.vy*dt;else d.vy=0;
  const travelled=Math.hypot(d.x-oldX,d.y-oldY);d.stride+=travelled;d.moving=travelled>.002;
  d.blockedTime=distance>2&&!d.moving&&!d.turnTimer&&speed>0?d.blockedTime+dt:0;
  return Math.hypot(tx-d.x,ty-d.y)<.8&&Math.hypot(d.vx,d.vy)<2;
 }
 function mouthPoint(d){
  return {x:d.x+(d.heading===1?12:d.heading===3?-12:0),y:d.y+(d.heading===2?-18:-12)};
 }
 function updateDogs(dt){
  if(!walkSettings.dogMotion)return;const s=currentWalk();
  s.dogs.forEach(d=>{
   if(s.ballGame?.dogId===d.i)return;
   d.time+=dt;const distance=Math.hypot(s.x-d.x,s.y-d.y),homeDistance=Math.hypot(s.x-d.homeX,s.y-d.homeY);
   const previous=d.engaged,enter=d.kind==='play'?54:46,leave=d.kind==='play'?65:58;
   d.engaged=homeDistance<(d.kind==='play'?74:64)&&distance<(previous?leave:enter);
   d.nearTime=d.engaged?d.nearTime+dt:0;
   if(d.engaged!==previous){d.greetTarget=null;d.nearTime=0;}
   if(walkReducedMotion.matches&&!d.engaged){d.moving=false;d.vx=0;d.vy=0;return;}
   d.pose='idle';const phaseAtStart=d.phase;let target={x:d.x,y:d.y},speed=0,dip=0;
   d.hold=Math.max(0,d.hold-dt);
   if(d.kind==='sniff'){
    if(d.engaged){
     d.pose='look';
     if(d.nearTime>2.2&&!s.moving&&!d.greetTarget&&distance>30){
      d.greetTarget={x:s.x+(d.x-s.x)/distance*30,y:s.y+(d.y-s.y)/distance*30};
     }
     if(d.greetTarget){target=d.greetTarget;speed=6;}
    }else if(d.hold>0){d.pose='sniff';dip=5;}
    else{
     if(!d.target){const spots=[[-18,8],[5,11],[0,0]],point=spots[d.route%spots.length];d.target={x:d.homeX+point[0],y:d.homeY+point[1]};}
     target=d.target;speed=7;d.pose='walk';
    }
   }else if(d.kind==='play'){
    d.pose=d.carrying?'carry':'play';
    if(d.phase==='fetch'){
     const side=d.ballX>=d.x?1:-1;
     if(!d.target)d.target={x:d.ballX-side*11,y:d.ballY+2};
     target=d.target;speed=17;
    }else if(d.phase==='pick'){
     dip=5;
     if(!d.hold){d.carrying=true;d.phase='return';d.target=null;d.returnForPlayer=false;}
    }else if(d.phase==='return'){
     if(!d.target||(d.engaged&&!d.returnForPlayer)){
      d.target=d.engaged&&distance>0?{x:s.x+(d.x-s.x)/distance*29,y:s.y+(d.y-s.y)/distance*29}:{x:d.homeX-13,y:d.homeY+10};
      d.returnForPlayer=d.engaged;
     }
     target=d.target;speed=16;
    }else if(d.phase==='offer'){
     d.pose='offer';
     if(!d.hold){d.carrying=false;d.phase='settle';d.hold=2.8;d.target=null;d.ballGroundY=d.y+5;}
    }else if(d.phase==='settle'){
     d.pose='offer';
     if(!d.hold&&!d.engaged){d.phase='wander';d.target={x:d.homeX+12,y:d.homeY-10};}
    }else if(d.phase==='wander'){target=d.target;speed=12;}
   }else if(d.kind==='rest'){
    d.pose=d.engaged?'look-rest':'rest';
    d.alert+=((d.engaged?1:0)-d.alert)*(1-Math.exp(-dt*4));
    d.lookX+=((d.engaged?Math.max(-1,Math.min(1,(s.x-d.x)/30)):0)-d.lookX)*(1-Math.exp(-dt*4));
   }
   const arrived=moveDog(d,target.x,target.y,speed,dt);
   const greetingPause=d.kind==='sniff'||d.kind==='play'&&(d.phase==='offer'||d.phase==='settle');
   if(d.engaged&&greetingPause&&speed===0&&!d.moving&&!d.turnTimer&&d.headDip<.6){
    const look=naturalHeading(s.x-d.x,s.y-d.y,d.heading);
    if(look!==d.heading){d.turnTo=look;d.turnTimer=.2;if((look+2)%4===d.heading)d.heading=(d.heading===1||d.heading===3)?0:1;}
   }
   if(d.kind==='sniff'&&!d.engaged&&d.target&&(arrived||d.blockedTime>.7)){
    d.target=null;d.route++;d.hold=2.3+(d.route%3)*.55;d.pose='sniff';
   }
   if(d.kind==='play'&&arrived&&d.phase===phaseAtStart){
    if(d.phase==='fetch'){d.phase='pick';d.hold=.7;d.target=null;const facing=d.ballX<d.x?3:1;if(d.heading!==facing){d.turnTo=facing;d.turnTimer=.18;}}
    else if(d.phase==='return'){d.phase='offer';d.hold=.85;d.target=null;}
    else if(d.phase==='wander'){d.phase='fetch';d.target=null;}
   }
   // Pause before lowering the head; the body never stretches or rocks.
   if(d.moving||d.turnTimer)dip=0;
   d.headDip+=(dip-d.headDip)*(1-Math.exp(-dt*6));
   if(d.kind==='play'){
    if(d.carrying){
     const mouth=mouthPoint(d),dx=mouth.x-d.ballX,dy=mouth.y-d.ballY,length=Math.hypot(dx,dy);
     const step=Math.min(length*(1-Math.exp(-dt*13)),48*dt);
     if(length>.001){d.ballX+=dx/length*step;d.ballY+=dy/length*step;}
    }else if(d.phase==='settle'){
     const delta=d.ballGroundY-d.ballY;
     d.ballY+=Math.sign(delta)*Math.min(Math.abs(delta)*(1-Math.exp(-dt*10)),36*dt);
    }
   }
  });
  updateEncounter();
 }
 function dogActivity(d){
  const game=currentWalk().ballGame?.dogId===d.i?currentWalk().ballGame:null;
  return game?(game.phase==='fetch'||game.phase==='flight'?'공 쫓아가기':game.phase==='return'?'공 가져오기':game.phase==='ready'?'던져줘!':'공놀이'):d.kind==='sniff'?(d.engaged?'천천히 인사':d.moving?'산책 중':'킁킁'):d.kind==='play'?'공놀이':d.kind==='rest'?'쉬는 중':'';
 }
 function dogNameLabel(d){const activity=dogActivity(d);return dogs[d.i].name+(activity?' · '+activity:'');}
 function updateDogLabels(dt){
  const s=currentWalk(),placed=[];walkContext.font='9px Galmuri11, sans-serif';
  [...s.dogs].sort((a,b)=>Number(b.i===s.near)-Number(a.i===s.near)||a.i-b.i).forEach(d=>{
   const close=Math.abs(s.x-d.x)<42&&Math.abs(s.y-d.y)<45,width=Math.ceil(walkContext.measureText(dogNameLabel(d)).width)+10;
   let target=close?Math.min(d.y-40,s.y-50):d.y-40;
   for(let pass=0;pass<s.dogs.length;pass++)for(const other of placed){if(Math.abs(d.x-other.x)<(width+other.width)/2+3&&Math.abs(target-other.y)<16)target=other.y-16;}
   placed.push({x:d.x,y:target,width});d.labelY+=(target-d.labelY)*(1-Math.exp(-dt*11));
  });
 }
 function drawBall(d){
  if(d.kind!=='play')return;const c=walkContext,x=Math.round(d.ballX),y=Math.round(d.ballY);
  const game=currentWalk().ballGame?.dogId===d.i?currentWalk().ballGame:null;
  if(game?.phase==='flight'){c.fillStyle='#8d7b5655';c.fillRect(x-3,Math.round(game.groundY),7,2);}
  else if(game?game.phase==='fetch'||game.phase==='pick':!d.carrying&&d.phase!=='settle'){c.fillStyle='#8d7b5655';c.fillRect(x-3,y+3,7,2);}
  c.fillStyle='#9e6657';c.fillRect(x-3,y-2,6,5);c.fillRect(x-2,y-3,4,7);
  c.fillStyle='#efb47b';c.fillRect(x-2,y-2,4,5);c.fillStyle='#ffe6af';c.fillRect(x-2,y-2,2,2);
 }
 function drawDog(d){
  const c=walkContext,im=walkDogImages[d.i],identity=[0,1,2,1,2][d.i]??0;
  if(d.kind==='rest'||d.kind==='neutral'){
   if(im?.complete&&im.naturalWidth){
    const headY=Math.round(2*(1-d.alert)),look=Math.round(d.lookX||0);
    c.drawImage(im,0,20,32,14,Math.round(d.x)-13,Math.round(d.y)-12,26,12);
    c.drawImage(im,0,0,32,20,Math.round(d.x)-13+look,Math.round(d.y)-28+headY,26,17);
   }
  }else if(walkDogAtlas.complete&&walkDogAtlas.naturalWidth){
   const moving=d.moving,side=d.heading===1||d.heading===3;
   const view=side?1:d.heading===2?2:0;
   let row=identity*3+view,frame=moving?Math.floor(d.stride/1.5)%8:0;
   if(!moving&&d.headDip>.8){row=9+identity;frame=Math.min(7,Math.round(d.headDip));}
   c.save();c.translate(Math.round(d.x),Math.round(d.y));if(d.heading===3)c.scale(-1,1);
   c.drawImage(walkDogAtlas,frame*36,row*36,36,36,-15,-27,30,30);c.restore();
  }
  const game=currentWalk().ballGame?.dogId===d.i?currentWalk().ballGame:null;
  if(!game)drawBall(d);
  walkLabel(dogNameLabel(d),Math.round(d.x),Math.round(d.labelY),d.i===currentWalk().near?'#fff0b1':'#fff9e9');
 }
