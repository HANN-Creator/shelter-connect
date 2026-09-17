const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const base = __dirname;
const walk = fs.readFileSync(base + '/walk-logic.js', 'utf8');
const source = fs.readFileSync(base + '/natural-dogs.js', 'utf8');
const state = {x:216,y:264,moving:false,dogs:[]};
const context = vm.createContext({Math,walkSettings:{dogMotion:true},walkReducedMotion:{matches:false},currentWalk:()=>state,updateEncounter(){}});
const bounds = walk.slice(walk.indexOf(' const walkBounds='),walk.indexOf(' const walkSlots='));
const collision = walk.match(/ function canDogStand\(d,x,y\)\{[\s\S]*?\n \}/)[0];
const circle = walk.match(/ function circleHitsBox[^\n]+/)[0];
vm.runInContext(bounds + circle + collision + source, context);
state.dogs = [['sniff',171,246],['play',280,247],['rest',249,343]].map(([kind,x,y],i)=>context.initNaturalDog({i,kind,x,y,homeX:x,homeY:y,time:0,stride:0,engaged:false,nearTime:0,moving:false}));
const phases=[];let lastBall=[303,240],maxBallStep=0,maxDogStep=0;
for(let step=0;step<3600;step++){
  const previous=state.dogs.map(d=>[d.x,d.y]);
  context.updateDogs(1/60);
  state.dogs.forEach((d,i)=>{
    const moved=Math.hypot(d.x-previous[i][0],d.y-previous[i][1]);maxDogStep=Math.max(maxDogStep,moved);
    assert(moved<=17/60+.001,'A dog teleported instead of walking.');
    assert(Math.hypot(d.x-state.x,d.y-state.y)>=19.99,'A dog passed through the visitor.');
  });
  const play=state.dogs[1];if(phases.at(-1)!==play.phase)phases.push(play.phase);
  maxBallStep=Math.max(maxBallStep,Math.hypot(play.ballX-lastBall[0],play.ballY-lastBall[1]));lastBall=[play.ballX,play.ballY];
}
assert.deepEqual(phases.slice(0,5),['fetch','pick','return','offer','settle']);
assert(maxBallStep<=.801,'The ball changed position abruptly.');
assert.equal(state.dogs[2].x,249);assert.equal(state.dogs[2].y,343);
context.walkSettings.dogMotion=false;
const paused=JSON.stringify(state.dogs);
for(let i=0;i<120;i++)context.updateDogs(1/60);
assert.equal(JSON.stringify(state.dogs),paused,'Pause changed a dog or ball position.');
console.log(JSON.stringify({phases,maxDogStep:+maxDogStep.toFixed(3),maxBallStep:+maxBallStep.toFixed(3),pause:'passed'}));
