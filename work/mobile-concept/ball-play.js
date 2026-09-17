 function ballPlayText(phase){
  return ({receiving:'내가 가져온 공이야. 같이 놀아줄래?',ready:'준비됐어! 공을 가볍게 던져줘.',windup:'어디로 던질지 보고 있어!',flight:'공을 따라가는 중이야!',fetch:'공을 가지러 가고 있어. 잠깐만 기다려줘.',pick:'찾았다! 다시 가져갈게.',return:'공을 물고 네 쪽으로 돌아가고 있어.',offered:'다시 가져왔어! 내 공을 받아줄래?'})[phase]||'';
 }
 function ballDog(s=currentWalk()){return s?.dogs.find(d=>d.i===s.ballGame?.dogId);}
 function playerHand(s){const seated=s.action==='sit'&&(walkReducedMotion.matches||s.actionTime>=.22);return {x:s.x+(s.flipX?-11:11),y:s.y-(seated?10:16)};}
 function ballRouteClear(d,from,to){
  const steps=Math.max(1,Math.ceil(Math.hypot(to.x-from.x,to.y-from.y)/4));
  for(let i=1;i<=steps;i++)if(!canDogStand(d,from.x+(to.x-from.x)*i/steps,from.y+(to.y-from.y)*i/steps))return false;
  return true;
 }
 function findThrowTarget(s,d){
  const angle=Math.atan2(d.y-s.y,d.x-s.x);
  for(const reach of [76,62,48])for(const turn of [0,-.55,.55,-1.1,1.1,-1.8,1.8,Math.PI]){
   const a=angle+turn,target={x:s.x+Math.cos(a)*reach,y:s.y+Math.sin(a)*reach};
   const side=target.x>=d.x?1:-1,landing={x:target.x+side*11,y:target.y-2};
   if(Math.hypot(target.x-d.x,target.y-d.y)<24||!canDogStand(d,landing.x,landing.y))continue;
   if(ballRouteClear(d,d,target))return {target,landing,side};
  }
  return null;
 }
 function ballInteraction(s,friend){
  const g=s.ballGame,d=g?ballDog(s):friend;
  if(!d)return {enabled:false,label:'공 던지기'};
  if(d.kind!=='play')return {enabled:false,label:'공놀이 기록 없음',dog:d};
  if(!walkSettings.dogMotion)return {enabled:false,label:'공놀이',dog:d};
  const distance=Math.hypot(s.x-d.x,s.y-d.y);
  if(g){
   const busy={receiving:'공 받는 중',windup:'공 던지는 중',flight:'공 날아가는 중',fetch:'공 가져오는 중',pick:'공 줍는 중',return:'공 가져오는 중'};
   if(busy[g.phase])return {enabled:false,label:busy[g.phase],dog:d};
   if(g.phase==='ready'){
    if(distance>54)return {enabled:false,label:d.name+' 가까이 가기',dog:d};
    const throwTarget=findThrowTarget(s,d);
    return {enabled:Boolean(throwTarget),label:throwTarget?'공 던지기':'넓은 곳에서 던지기',action:'throw',dog:d,throwTarget};
   }
  }
  const offered=g?.phase==='offered'||d.phase==='offer'||d.phase==='settle';
  return {enabled:offered&&distance<=42,label:offered?(distance<=42?'공 받기':d.name+' 가까이 가기'):'공 가져오는 중',action:'receive',dog:d};
 }
 function refreshBallControl(s,friend){
  const button=q('#pw-ball'),interaction=ballInteraction(s,friend);
  const d=interaction.dog,g=s.ballGame,busy=g&&!['ready','offered'].includes(g.phase),inRange=d&&Math.hypot(s.x-d.x,s.y-d.y)<=(g?.phase==='ready'?54:42);
  button.hidden=!(d?.kind==='play'&&(busy||inRange));
  button.disabled=!interaction.enabled;
  const compact={'공 받는 중':'받는 중','공 던지는 중':'던지는 중','공 날아가는 중':'날아가는 중','공 가져오는 중':'가져오는 중','공 줍는 중':'줍는 중','넓은 곳에서 던지기':'공 던지기'};
  text('#pw-ball-label',compact[interaction.label]??interaction.label);
  button.setAttribute('aria-label',(interaction.dog?dogs[interaction.dog.i].name+' · ':'')+interaction.label+(interaction.enabled?' · E 키':''));
 }
 function interactWithBall(){
  if(screenName!=='walk')return;
  const s=currentWalk(),interaction=ballInteraction(s,s.dogs.find(d=>d.i===s.near));
  if(!interaction.enabled)return;
  stopWalking();const d=interaction.dog;
  if(interaction.action==='receive'){
   s.flipX=d.x<s.x;
   s.ballGame={dogId:d.i,phase:'receiving',time:0,from:{x:d.ballX,y:d.ballY},path:[],round:(s.ballGame?.round??0)};
   d.carrying=false;d.target=null;d.vx=0;d.vy=0;d.moving=false;
   setPlayerAction(s,'pull');
  }else{
   const g=s.ballGame;Object.assign(g,interaction.throwTarget);
   s.flipX=g.landing.x<s.x;
   g.phase='windup';g.time=0;g.from={x:d.ballX,y:d.ballY};g.path=[];g.pathGoal=null;g.round++;
   setPlayerAction(s,'push');
  }
  updateEncounter();drawWalk();runWalk();
 }
 // Short paths usually stay direct. Re-plan around obstacles if the visitor
 // walks elsewhere while the dog is returning with the ball.
 function planBallRoute(d,target){
  if(ballRouteClear(d,d,target))return [target];
  const grid=14,cols=Math.floor((walkBounds.right-walkBounds.left)/grid)+1,rows=Math.floor((walkBounds.bottom-walkBounds.top)/grid)+1;
  const point=id=>({x:walkBounds.left+(id%cols)*grid,y:walkBounds.top+Math.floor(id/cols)*grid});
  const valid=new Map(),isFree=id=>{if(!valid.has(id)){const p=point(id);valid.set(id,canDogStand(d,p.x,p.y));}return valid.get(id);};
  const starts=[];
  for(let id=0;id<cols*rows;id++){const p=point(id);if(Math.hypot(p.x-d.x,p.y-d.y)<=24&&isFree(id)&&ballRouteClear(d,d,p))starts.push(id);}
  if(!starts.length)return [];
  const cost=new Map(),parent=new Map(),open=new Set(starts),closed=new Set();
  starts.forEach(id=>{const p=point(id);cost.set(id,Math.hypot(p.x-d.x,p.y-d.y));});
  while(open.size&&closed.size<1600){
   let best=-1,score=Infinity;
   open.forEach(id=>{const p=point(id),f=cost.get(id)+Math.hypot(p.x-target.x,p.y-target.y);if(f<score){score=f;best=id;}});
   open.delete(best);closed.add(best);const p=point(best);
   if(ballRouteClear(d,p,target)){
    const path=[target,p];let at=best;while(parent.has(at)){at=parent.get(at);path.push(point(at));}return path.reverse();
   }
   const cx=best%cols,cy=Math.floor(best/cols);
   for(let dy=-1;dy<=1;dy++)for(let dx=-1;dx<=1;dx++){
    if(!dx&&!dy||cx+dx<0||cx+dx>=cols||cy+dy<0||cy+dy>=rows)continue;
    const next=(cy+dy)*cols+cx+dx;if(closed.has(next)||!isFree(next))continue;
    const n=point(next);if(!ballRouteClear(d,p,n))continue;
    const nextCost=cost.get(best)+Math.hypot(n.x-p.x,n.y-p.y);
    if(nextCost<(cost.get(next)??Infinity)){cost.set(next,nextCost);parent.set(next,best);open.add(next);}
   }
  }
  return [];
 }
 function followBallRoute(d,g,target,speed,dt){
  g.repathWait=Math.max(0,(g.repathWait??0)-dt);
  if(!g.path?.length&&!g.repathWait||!g.pathGoal||Math.hypot(target.x-g.pathGoal.x,target.y-g.pathGoal.y)>8||d.blockedTime>.65&&!g.repathWait){
   g.path=planBallRoute(d,target);g.pathGoal={...target};g.repathWait=.6;
  }
  if(!g.path.length){moveDog(d,d.x,d.y,0,dt);return false;}
  while(g.path.length>1&&ballRouteClear(d,d,g.path[1]))g.path.shift();
  const next=g.path[0],arrived=moveDog(d,next.x,next.y,speed,dt);
  if(arrived&&g.path.length>1){g.path.shift();return false;}return arrived;
 }
 function returnBallTarget(s,d){
  const angle=Math.atan2(d.y-s.y,d.x-s.x);
  for(const turn of [0,-.5,.5,-1,1,-1.6,1.6,Math.PI]){
   const target={x:s.x+Math.cos(angle+turn)*29,y:s.y+Math.sin(angle+turn)*29};
   if(canDogStand(d,target.x,target.y))return target;
  }
  return {x:d.x,y:d.y};
 }
 function faceBallPlayer(s,d){
  if(d.moving||d.turnTimer||d.headDip>.8)return;
  const heading=naturalHeading(s.x-d.x,s.y-d.y,d.heading);
  if(heading!==d.heading){d.turnTo=heading;d.turnTimer=.18;if((heading+2)%4===d.heading)d.heading=heading===1||heading===3?0:1;}
 }
 function carryGameBall(d,dt){
  const mouth=mouthPoint(d),dx=mouth.x-d.ballX,dy=mouth.y-d.ballY,distance=Math.hypot(dx,dy),step=Math.min(distance*(1-Math.exp(-dt*15)),58*dt);
  if(distance>.001){d.ballX+=dx/distance*step;d.ballY+=dy/distance*step;}
 }
 function updateBallPlay(dt){
  const s=currentWalk(),g=s?.ballGame,d=ballDog(s);if(!g||!d||!walkSettings.dogMotion)return;
  g.time+=dt;d.time+=dt;d.engaged=Math.hypot(s.x-d.x,s.y-d.y)<65;d.pose=d.carrying?'carry':'play';let dip=0;
  if(g.phase==='receiving'){
   moveDog(d,d.x,d.y,0,dt);faceBallPlayer(s,d);
   const hand=playerHand(s),t=Math.min(1,g.time/.6),ease=t*t*(3-2*t);
   d.ballX=g.from.x+(hand.x-g.from.x)*ease;d.ballY=g.from.y+(hand.y-g.from.y)*ease;
   if(t===1){g.phase='ready';g.time=0;}
  }else if(g.phase==='ready'||g.phase==='windup'){
   moveDog(d,d.x,d.y,0,dt);faceBallPlayer(s,d);const hand=playerHand(s);
   if(g.phase==='windup'){const t=Math.min(1,g.time/.25);d.ballX=g.from.x+(hand.x-g.from.x)*t;d.ballY=g.from.y+(hand.y-g.from.y)*t;}
   else{const dx=hand.x-d.ballX,dy=hand.y-d.ballY,length=Math.hypot(dx,dy),step=Math.min(length,160*dt);if(length>.001){d.ballX+=dx/length*step;d.ballY+=dy/length*step;}}
   if(g.phase==='windup'&&g.time>=.28){g.phase='flight';g.time=0;g.from={x:d.ballX,y:d.ballY};g.fromGroundY=s.y;g.groundY=s.y;}
  }else if(g.phase==='flight'){
   const t=Math.min(1,g.time/.7);d.ballX=g.from.x+(g.landing.x-g.from.x)*t;
   d.ballY=g.from.y+(g.landing.y-g.from.y)*t-Math.sin(Math.PI*t)*(walkReducedMotion.matches?0:24);
   g.groundY=g.fromGroundY+(g.landing.y+4-g.fromGroundY)*t;
   if(g.time>.16)followBallRoute(d,g,g.target,26,dt);else moveDog(d,d.x,d.y,0,dt);
   if(t===1){g.phase='fetch';g.time=0;}
  }else if(g.phase==='fetch'){
   if(followBallRoute(d,g,g.target,26,dt)){g.phase='pick';g.time=0;d.turnTo=g.side>0?1:3;d.turnTimer=.16;}
  }else if(g.phase==='pick'){
   moveDog(d,d.x,d.y,0,dt);dip=5;
   if(g.time>.65){d.carrying=true;g.phase='return';g.time=0;g.path=[];g.pathGoal=null;g.returnTarget=returnBallTarget(s,d);}
  }else if(g.phase==='return'){
   if(Math.hypot(s.x-g.returnTarget.x,s.y-g.returnTarget.y)>42||!canDogStand(d,g.returnTarget.x,g.returnTarget.y))g.returnTarget=returnBallTarget(s,d);
   const arrived=followBallRoute(d,g,g.returnTarget,23,dt);
   if(arrived&&Math.hypot(s.x-d.x,s.y-d.y)<=42){g.phase='offered';g.time=0;g.path=[];}
  }else if(g.phase==='offered'){
   moveDog(d,d.x,d.y,0,dt);faceBallPlayer(s,d);d.pose='offer';
  }
  d.phase=g.phase;
  if(d.moving||d.turnTimer)dip=0;
  d.headDip+=(dip-d.headDip)*(1-Math.exp(-dt*6));
  if(d.carrying)carryGameBall(d,dt);
 }
