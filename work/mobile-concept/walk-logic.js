 const walkReducedMotion=window.matchMedia('(prefers-reduced-motion: reduce)');
 const walkSettings={speed:42,dogMotion:true};
 // PUSH/PULL are short interaction gestures, separate from locomotion.
 const playerClips={idle:{row:0,count:2},walk:{row:1,count:4,step:5},run:{row:2,count:4,step:6},push:{row:3,count:4,duration:.56},pull:{row:4,count:4,duration:.6},sit:{row:5,count:2}};
 const walkStates=new Map();
 let walkFromShelter=null,walkFrame=0,walkLastTime=0,walkPointer=null,walkAxis={x:0,y:0};
 const walkKeys=new Set();
 const walkCanvas=q('#pw-canvas'),walkContext=walkCanvas.getContext('2d');
 const walkWorld=new Image(),walkCharacter=new Image();
 walkWorld.src='__GARDEN__';walkCharacter.src='__WALKER__';
 const walkDogImages=[],walkDogAtlas=new Image();walkDogAtlas.src='__DOGWALKS__';
 const walkBounds={left:32,top:34,right:414,bottom:412};
 const walkObstacles=[
  {x:114,y:70,w:120,h:108},{x:311,y:153,w:49,h:50},
  {x:76,y:282,w:46,h:35},{x:302,y:318,w:75,h:46},{x:272,y:280,w:36,h:18},
  {x:33,y:389,w:153,h:18},{x:263,y:390,w:153,h:18},
  {x:43,y:50,w:44,h:53},{x:60,y:151,w:36,h:44},{x:274,y:61,w:40,h:52},
  {x:359,y:113,w:40,h:52},{x:367,y:250,w:38,h:47},{x:52,y:307,w:42,h:53},
  {x:318,y:351,w:35,h:46},{x:101,y:363,w:31,h:40},
  {x:124,y:326,w:16,h:10},{x:305,y:162,w:18,h:10}
 ];
 const walkSlots=[{x:171,y:246},{x:280,y:247},{x:249,y:343},{x:92,y:235},{x:270,y:208},{x:193,y:354}];
 function addWalkFriends(){
  // Fictional profile examples for testing encounters inside one partner shelter.
  [{source:1,name:'봄이'},{source:2,name:'호두'}].forEach(({source,name})=>{
   const original=dogs[source],d=JSON.parse(JSON.stringify(original)),rename=t=>t.split(original.name).join(name);
   d.name=name;d.greeting=rename(d.greeting);d.shelterId='ongi';if(d.motion)Object.keys(d.motion.sources).forEach(topic=>{d.motion.sources[topic]=rename(d.motion.sources[topic]);});
   Object.values(d.records).filter(Boolean).forEach(r=>{r.raw=rename(r.raw);r.speech=rename(r.speech);r.summary=rename(r.summary);});
   dogs.push(d);sprites.push(sprites[source]);sessions.push({messages:[],topics:new Set(),pending:[],ready:false,revealed:false,checks:[false,false,false,false],family:'',care:'',draftSaved:false});
  });
 }
 function currentWalk(){return walkStates.get(selectedShelterId);}
 function ensureWalk(){
  if(!walkStates.has(selectedShelterId))walkStates.set(selectedShelterId,{x:216,y:264,facing:0,flipX:false,stride:0,action:'idle',actionTime:0,idleTime:0,gaitPhase:0,cameraX:88,cameraY:44.48,near:-1,drawnNear:-2,dogs:[]});
  const s=currentWalk(),previous=new Map(s.dogs.map(d=>[d.i,d]));
  s.dogs=dogs.flatMap((d,i)=>d.shelterId===selectedShelterId?[i]:[]).slice(0,walkSlots.length).map((i,slot)=>{
   const profile=dogs[i],motion=profile.motion,kind=motion&&motion.topics.every(topic=>profile.records[topic]?.raw===motion.sources[topic])?motion.kind:'neutral';
   const old=previous.get(i);if(old&&old.kind===kind)return old;
   return initNaturalDog({i,...walkSlots[slot],homeX:walkSlots[slot].x,homeY:walkSlots[slot].y,kind,time:0,stride:0,moving:false,facing:1,nearTime:0,engaged:false,pose:kind==='rest'?'rest':'idle'});
  });
  if(s.ballGame&&!s.dogs.some(d=>d.i===s.ballGame.dogId&&d.kind==='play')){s.ballGame=null;setPlayerAction(s,'idle');}
  return s;
 }
 const dogMotionLabels={sniff:'천천히 냄새 맡기',play:'공과 함께 종종걸음',rest:'매트에서 쉬기',neutral:'기록 기다리는 중'};
 function seedDogMotions(){
  [{kind:'sniff',topics:['walk','people']},{kind:'play',topics:['people','walk']},{kind:'rest',topics:['people']}].forEach((motion,i)=>{dogs[i].motion={...motion,sources:Object.fromEntries(motion.topics.map(topic=>[topic,dogs[i].records[topic].raw]))};});
 }
 function canDogStand(d,x,y){
  const s=currentWalk();if(x<walkBounds.left||x>walkBounds.right||y<walkBounds.top||y>walkBounds.bottom)return false;
  if(walkObstacles.some(b=>circleHitsBox(x,y,7,b))||Math.hypot(x-s.x,y-s.y)<20)return false;
  return !s.dogs.some(other=>other!==d&&Math.hypot(x-other.x,y-other.y)<23);
 }
 function dogMotionText(d){
  const game=currentWalk()?.ballGame;
  if(game?.dogId===d.i)return ballPlayText(game.phase);
  if(d.kind==='sniff')return d.engaged?'조금 기다려주면 내가 천천히 다가갈게.':'냄새를 맡으며 천천히 둘러보는 중이야.';
  if(d.kind==='play')return d.engaged?(d.carrying||d.phase==='offer'||d.phase==='settle'?'공을 가져왔어. 나랑 같이 놀아볼래?':'공을 가지러 가는 중이야. 잠깐 기다려줄래?'):'공을 갖고 종종걸음으로 오가고 있어.';
  if(d.kind==='rest')return '나는 여기서 쉬는 중이야. 천천히 인사해줘.';
  return '보호소가 기록한 내 이야기를 들려줄게.';
 }
 function circleHitsBox(x,y,r,b){const cx=Math.max(b.x,Math.min(x,b.x+b.w)),cy=Math.max(b.y,Math.min(y,b.y+b.h));return (x-cx)**2+(y-cy)**2<r*r;}
 function canStand(x,y){
  if(x<walkBounds.left||x>walkBounds.right||y<walkBounds.top||y>walkBounds.bottom)return false;
  if(walkObstacles.some(b=>circleHitsBox(x,y,6,b)))return false;
  return !currentWalk().dogs.some(d=>Math.hypot(x-d.x,y-d.y)<20);
 }
 function walkStep(dx,dy){
  const s=currentWalk();if(!s)return;const beforeX=s.x,beforeY=s.y;
  if(canStand(s.x+dx,s.y))s.x+=dx;if(canStand(s.x,s.y+dy))s.y+=dy;
  const moved=Math.hypot(s.x-beforeX,s.y-beforeY);s.stride+=moved;s.moving=moved>.001;
  if(Math.abs(dx)>Math.abs(dy))s.facing=dx<0?2:3;else if(dy)s.facing=dy<0?1:0;
  if(Math.abs(dx)>.001)s.flipX=dx<0;
  updateEncounter();return moved;
 }
 function walkInput(){
  let x=walkAxis.x,y=walkAxis.y;
  const left=walkKeys.has('ArrowLeft')||walkKeys.has('KeyA'),right=walkKeys.has('ArrowRight')||walkKeys.has('KeyD');
  const up=walkKeys.has('ArrowUp')||walkKeys.has('KeyW'),down=walkKeys.has('ArrowDown')||walkKeys.has('KeyS');
  x+=Number(right)-Number(left);y+=Number(down)-Number(up);
  const length=Math.hypot(x,y);if(length>1){x/=length;y/=length;}
  return {x,y,power:Math.min(1,length),keyboard:left||right||up||down,run:walkKeys.has('ShiftLeft')||walkKeys.has('ShiftRight')};
 }
 function setPlayerAction(s,action){
  if(s.action===action)return;
  if(s.action==='idle'||s.action==='sit')s.gaitPhase=0;
  s.action=action;s.actionTime=0;
 }
 function advancePlayer(dt,input){
  const s=currentWalk();if(!s)return;
  if(playerClips[s.action].duration){
   s.moving=false;s.idleTime=0;if(s.ballGame&&!walkSettings.dogMotion)return;
   s.actionTime+=dt;if(s.actionTime>=playerClips[s.action].duration)setPlayerAction(s,'idle');return;
  }
  if(input.power<=.001){
   s.moving=false;s.idleTime=(s.idleTime??0)+dt;
   setPlayerAction(s,s.idleTime>=10?'sit':'idle');s.actionTime+=dt;return;
  }
  s.idleTime=0;
  // Separate enter/exit thresholds prevent flickering at the walk/run boundary.
  const running=input.keyboard?input.run:input.power>=(s.action==='run'?.68:.82);
  // Analog speed stays continuous when the animation changes to RUN.
  const speed=walkSettings.speed*(input.keyboard?(running?1.6:1):1.6);
  const moved=walkStep(input.x*speed*dt,input.y*speed*dt);
  setPlayerAction(s,moved>.001?(running?'run':'walk'):'idle');
  s.actionTime+=dt;
  if(s.moving)s.gaitPhase=(s.gaitPhase+moved/playerClips[s.action].step)%4;
 }
 function playerFrame(s){
  const clip=playerClips[s.action];
  if(s.action==='sit')return {row:clip.row,frame:walkReducedMotion.matches?1:Math.min(1,Math.floor(s.actionTime/.22))};
  if(clip.duration)return {row:clip.row,frame:Math.min(clip.count-1,Math.floor(s.actionTime/clip.duration*clip.count))};
  const frame=s.action==='idle'?(walkReducedMotion.matches?0:Math.floor(s.actionTime/.65)%clip.count):Math.floor(s.gaitPhase)%clip.count;
  return {row:clip.row,frame};
 }
 function updateEncounter(){
  const s=currentWalk();if(!s)return;
  const choices=s.dogs.map(d=>({...d,distance:Math.hypot(s.x-d.x,s.y-d.y)})).filter(d=>d.distance<=(d.i===s.near?39:34)).sort((a,b)=>a.distance-b.distance);
  s.near=choices[0]?.i??-1;
  const friend=s.dogs.find(d=>d.i===s.near);
  refreshBallControl(s,friend);
  const encounterKey=s.near+':'+Boolean(friend?.engaged)+':'+(s.ballGame?.phase??'')+':'+(friend?.kind==='play'?Boolean(friend.carrying||friend.phase==='offer'||friend.phase==='settle'):'');
  if(s.drawnNear===encounterKey)return;s.drawnNear=encounterKey;
  const talk=q('#pw-talk');talk.disabled=s.near<0;talk.hidden=s.near<0;
  talk.setAttribute('aria-label',friend?dogs[friend.i].name+'와 대화하기':'가까운 강아지와 대화하기');
  text('#pw-nearby',friend?dogs[friend.i].name+'. '+dogMotionText(friend)+' 대화 버튼을 사용할 수 있어요.':'가까운 강아지에게 걸어가면 상호작용 버튼이 나타나요.');
 }
 function walkLabel(label,x,y,color='#fff9e9',ink='#54614b'){
  const c=walkContext;c.font='9px Galmuri11, sans-serif';const width=Math.ceil(c.measureText(label).width)+10;
  c.fillStyle='#68785633';c.fillRect(Math.round(x-width/2+1),Math.round(y-1),width,14);
  c.fillStyle=color;c.fillRect(Math.round(x-width/2),Math.round(y-3),width,13);c.fillStyle=ink;c.textAlign='center';c.textBaseline='middle';c.fillText(label,Math.round(x),Math.round(y+3));
 }
 function drawDogProp(d){
  const c=walkContext;
  if(d.kind==='rest'){
   c.fillStyle='#b28c76';c.fillRect(Math.round(d.homeX-20),Math.round(d.homeY-6),40,15);
   c.fillStyle='#edcf9e';c.fillRect(Math.round(d.homeX-18),Math.round(d.homeY-5),36,12);
   c.fillStyle='#c8a575';for(let i=0;i<4;i++)c.fillRect(Math.round(d.homeX-15+i*9),Math.round(d.homeY-4),2,10);
  }
 }
__NATURAL_DOG_LOGIC__
__BALL_PLAY_LOGIC__
 function drawWalk(){
  if(screenName!=='walk')return;const s=currentWalk();if(!s)return;
  const c=walkContext,w=walkCanvas.width,h=walkCanvas.height;c.imageSmoothingEnabled=false;c.fillStyle='#9bcfc5';c.fillRect(0,0,w,h);
  const camX=Math.round(s.cameraX),camY=Math.round(s.cameraY);
  // Extend the surrounding water so the visitor stays above the thumb controls.
  c.fillStyle='#b6e0ce';
  for(let wy=Math.floor(camY/9)*9+3;wy<camY+h;wy+=9){const offset=((Math.floor(wy/9)*7)%10+10)%10;for(let wx=Math.floor(camX/22)*22+offset;wx<camX+w;wx+=22)c.fillRect(wx-camX,wy-camY,6,1);}
  if(walkWorld.complete&&walkWorld.naturalWidth)c.drawImage(walkWorld,-camX,-camY);
  c.save();c.translate(-camX,-camY);
  s.dogs.forEach(drawDogProp);
  const nearby=s.dogs.find(d=>d.i===s.near);
  if(nearby){c.strokeStyle='#f5f4bc';c.lineWidth=2;c.beginPath();c.ellipse(nearby.x,nearby.y,18,8,0,0,Math.PI*2);c.stroke();}
  const actors=[...s.dogs.map(d=>({x:d.x,y:d.y,dog:d,actorType:'dog'})),{x:s.x,y:s.y,actorType:'person'}].sort((a,b)=>a.y-b.y);
  actors.forEach(a=>{
   const x=Math.round(a.x),y=Math.round(a.y);c.fillStyle='#67835555';c.beginPath();c.ellipse(x,y,10,3,0,0,Math.PI*2);c.fill();
   if(a.actorType==='dog'){
    drawDog(a.dog);
   }else if(walkCharacter.complete&&walkCharacter.naturalWidth){
    const {row,frame}=playerFrame(s);
    // These are action clips, not directional views. Mirror only left/right.
    c.save();c.translate(x,y);if(s.flipX)c.scale(-1,1);
    c.drawImage(walkCharacter,frame*24,row*32,24,32,-24,-52,48,64);c.restore();
    c.fillStyle='#fff9e9';c.beginPath();c.moveTo(x-3,y-41);c.lineTo(x+3,y-41);c.lineTo(x,y-38);c.fill();
   }
  });
  const playingDog=s.ballGame&&s.dogs.find(d=>d.i===s.ballGame.dogId);if(playingDog)drawBall(playingDog);
  c.restore();
  walkCanvas.dataset.playerX=s.x.toFixed(2);walkCanvas.dataset.playerY=s.y.toFixed(2);walkCanvas.dataset.near=s.near>=0?dogs[s.near].name:'';
  walkCanvas.dataset.playerAction=s.action;walkCanvas.dataset.playerFrame=String(playerFrame(s).frame);walkCanvas.dataset.playerFlip=String(s.flipX);
  walkCanvas.dataset.ballPlay=s.ballGame?.phase??'none';
  walkCanvas.dataset.dogs=JSON.stringify(s.dogs.map(d=>({name:dogs[d.i].name,x:+d.x.toFixed(2),y:+d.y.toFixed(2),pose:d.pose,phase:d.phase,moving:d.moving,heading:d.heading,speed:+Math.hypot(d.vx,d.vy).toFixed(2),ball:d.kind==='play'?[+d.ballX.toFixed(2),+d.ballY.toFixed(2),d.carrying]:null})));
 }
 function updateCamera(dt){
  const s=currentWalk(),tx=s.x-walkCanvas.width/2,ty=s.y-walkCanvas.height*.49;
  const smooth=1-Math.exp(-14*dt);s.cameraX+=(tx-s.cameraX)*smooth;s.cameraY+=(ty-s.cameraY)*smooth;
  return Math.abs(tx-s.cameraX)+Math.abs(ty-s.cameraY)>.2;
 }
 function walkTick(now){
  walkFrame=0;if(screenName!=='walk'||document.hidden)return;
  const dt=Math.min(.034,Math.max(.001,(now-walkLastTime)/1000));walkLastTime=now;const input=walkInput();
  advancePlayer(dt,input);
  updateBallPlay(dt);
  updateDogs(dt);updateDogLabels(dt);
  const settling=updateCamera(dt);drawWalk();
  if(input.x||input.y||settling||walkSettings.dogMotion||!walkReducedMotion.matches)walkFrame=requestAnimationFrame(walkTick);
 }
 function runWalk(){if(!walkFrame&&screenName==='walk'){walkLastTime=performance.now();walkFrame=requestAnimationFrame(walkTick);}}
 function stopWalking(){
  if(walkFrame)cancelAnimationFrame(walkFrame);walkFrame=0;walkKeys.clear();walkAxis={x:0,y:0};q('#pw-knob').style.transform='translate(0px,0px)';
  if(walkPointer!==null){const id=walkPointer;walkPointer=null;try{q('#pw-stick').releasePointerCapture(id);}catch{}}
  if(currentWalk()){currentWalk().moving=false;if(['walk','run'].includes(currentWalk().action))setPlayerAction(currentWalk(),'idle');}drawWalk();
 }
 function enterWalk(){
  if(!shelterById(selectedShelterId)?.connected)return;
  const s=ensureWalk();s.drawnNear=-2;text('#pw-shelter-title',shelterById(selectedShelterId).name+' · 앞마당');updateEncounter();drawWalk();runWalk();
  walkCanvas.setAttribute('aria-label','도트 마당. '+s.dogs.map(d=>dogs[d.i].name+': '+dogMotionLabels[d.kind]).join('. ')+'. 왼쪽 아래 조이스틱으로 가까이 가면 오른쪽 아래에 대화와 공놀이 버튼이 나타나요.');
 }
 function setupWalk(){
  seedDogMotions();addWalkFriends();
  sprites.forEach((src,i)=>{const im=new Image();im.onload=drawWalk;im.src=src;walkDogImages[i]=im;});walkWorld.onload=drawWalk;walkCharacter.onload=drawWalk;walkDogAtlas.onload=drawWalk;
  if(document.fonts)document.fonts.ready.then(drawWalk);
  q('#pw-enter').addEventListener('click',()=>show('walk'));q('#pw-list').addEventListener('click',()=>show('shelter'));
  walkReducedMotion.addEventListener('change',()=>{updateEncounter();drawWalk();runWalk();});
  q('#pw-talk').addEventListener('click',()=>{updateEncounter();const s=currentWalk();if(!s||s.near<0)return;active=s.near;walkFromShelter=selectedShelterId;show('chat');});
  q('#pw-ball').addEventListener('click',interactWithBall);
  const stick=q('#pw-stick');
  function track(e){
   if(e.pointerId!==walkPointer||screenName!=='walk')return;
   if(e.type==='pointermove'&&e.buttons===0){stopWalking();runWalk();return;}
   const r=stick.getBoundingClientRect(),dx=e.clientX-r.left-r.width/2,dy=e.clientY-r.top-r.height/2;
   const length=Math.hypot(dx,dy),max=r.width/2-25,ratio=length>max?max/length:1;
   q('#pw-knob').style.transform='translate('+(dx*ratio).toFixed(1)+'px,'+(dy*ratio).toFixed(1)+'px)';
   const magnitude=Math.max(0,Math.min(1,(length-6)/(max-6)));walkAxis=length?{x:dx/length*magnitude,y:dy/length*magnitude}:{x:0,y:0};runWalk();
  }
  stick.addEventListener('pointerdown',e=>{if(walkPointer!==null||screenName!=='walk'||e.button>0)return;e.preventDefault();walkPointer=e.pointerId;stick.setPointerCapture(e.pointerId);stick.focus({preventScroll:true});track(e);});
  stick.addEventListener('pointermove',track);
  const release=e=>{if(e.pointerId!==walkPointer)return;stopWalking();runWalk();};
  ['pointerup','pointercancel','lostpointercapture'].forEach(event=>stick.addEventListener(event,release));
  ['pointerup','pointercancel'].forEach(event=>window.addEventListener(event,release));
  const valid=new Set(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','KeyW','KeyA','KeyS','KeyD']),shiftKeys=new Set(['ShiftLeft','ShiftRight']);
  root.addEventListener('keydown',e=>{
   if(screenName==='walk'&&e.code==='KeyE'&&!e.target.matches('input,textarea,select')){e.preventDefault();if(!e.repeat)interactWithBall();return;}
   if(screenName==='walk'&&shiftKeys.has(e.code)){walkKeys.add(e.code);runWalk();return;}
   if(screenName!=='walk'||!valid.has(e.code)||e.target.matches('input,textarea,select'))return;e.preventDefault();
   if(e.shiftKey)walkKeys.add('ShiftLeft');
   if(!walkKeys.has(e.code)){walkKeys.add(e.code);if(!playerClips[currentWalk().action].duration)advancePlayer(4/walkSettings.speed,walkInput());drawWalk();}runWalk();
  });
  root.addEventListener('keyup',e=>{if(!valid.has(e.code)&&!shiftKeys.has(e.code))return;if(screenName==='walk'&&valid.has(e.code))e.preventDefault();walkKeys.delete(e.code);if(shiftKeys.has(e.code)){walkKeys.delete('ShiftLeft');walkKeys.delete('ShiftRight');}if(currentWalk()&&!walkInput().power){currentWalk().moving=false;if(!playerClips[currentWalk().action].duration)setPlayerAction(currentWalk(),'idle');drawWalk();}runWalk();});
  window.addEventListener('blur',stopWalking);window.addEventListener('focus',runWalk);document.addEventListener('visibilitychange',()=>{if(document.hidden)stopWalking();else runWalk();});
 }
