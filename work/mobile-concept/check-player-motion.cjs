const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(__dirname + '/walk-logic.js', 'utf8');
const clips = source.slice(source.indexOf(' const playerClips='), source.indexOf(' const walkStates='));
const controller = source.slice(source.indexOf(' function walkInput(){'), source.indexOf(' function updateEncounter(){'));
const sandbox = { assert };
vm.createContext(sandbox);
vm.runInContext(`
 const walkSettings={speed:42}, walkReducedMotion={matches:false};
 const walkKeys=new Set(); let walkAxis={x:0,y:0}, state, blocked=false;
 const currentWalk=()=>state;
 function reset(){state={x:0,y:0,action:'idle',actionTime:0,gaitPhase:0,moving:false};walkAxis={x:0,y:0};walkKeys.clear();blocked=false;}
 function walkStep(dx,dy){const moved=blocked?0:Math.hypot(dx,dy);state.moving=moved>.001;if(!blocked){state.x+=dx;state.y+=dy;}return moved;}
 ${clips}
 ${controller}
 function sample(seconds=1, hz=60){const frames=new Set();for(let i=0;i<seconds*hz;i++){advancePlayer(1/hz,walkInput());frames.add(playerFrame(state).row+':'+playerFrame(state).frame);}return [...frames].sort();}

 reset(); walkAxis={x:.5,y:0};
 assert.deepEqual(sample(),['1:0','1:1','1:2','1:3']);
 assert.equal(state.action,'walk'); assert.ok(Math.abs(state.x-33.6)<1e-8);
 reset(); walkAxis={x:1,y:0};
 assert.deepEqual(sample(),['2:0','2:1','2:2','2:3']);
 assert.equal(state.action,'run'); assert.ok(Math.abs(state.x-67.2)<1e-8);
 walkAxis.x=.76; advancePlayer(1/60,walkInput()); assert.equal(state.action,'run');
 walkAxis.x=.67; advancePlayer(1/60,walkInput()); assert.equal(state.action,'walk');
 walkAxis.x=.81; advancePlayer(1/60,walkInput()); assert.equal(state.action,'walk');
 walkAxis.x=.83; advancePlayer(1/60,walkInput()); assert.equal(state.action,'run');
 walkAxis={x:0,y:0}; const stoppedX=state.x; advancePlayer(1/60,walkInput());
 assert.equal(state.action,'idle'); sample(); assert.equal(state.x,stoppedX);
 assert.deepEqual(sample(1.3),['0:0','0:1']);

 reset(); walkKeys.add('ArrowRight'); sample();
 assert.equal(state.action,'walk'); assert.ok(Math.abs(state.x-42)<1e-8);
 walkKeys.add('ShiftLeft'); sample(); assert.equal(state.action,'run');
 blocked=true; const wallX=state.x; sample(); assert.equal(state.action,'idle'); assert.equal(state.x,wallX);

 reset(); walkKeys.add('ArrowRight'); walkKeys.add('ArrowDown'); sample();
 assert.ok(Math.abs(Math.hypot(state.x,state.y)-42)<1e-8);
 reset(); walkAxis={x:1,y:0}; sample(1,30); const at30=state.x;
 reset(); walkAxis={x:1,y:0}; sample(1,120); assert.ok(Math.abs(state.x-at30)<1e-8);
 reset(); sample(9.95); assert.equal(state.action,'idle');
 sample(.1); assert.equal(state.action,'sit');sample(2);assert.equal(playerFrame(state).row,5);assert.equal(playerFrame(state).frame,1);
 walkAxis={x:1,y:0};advancePlayer(1/60,walkInput());assert.equal(state.action,'run');assert.equal(state.idleTime,0);
 blocked=true;sample(12);assert.equal(state.action,'idle');
 walkAxis={x:0,y:0};sample(9);assert.equal(state.action,'idle');sample(1.1);assert.equal(state.action,'sit');
 setPlayerAction(state,'pull');sample(1);assert.equal(state.action,'idle');assert.ok(state.idleTime<1);
 reset(); walkReducedMotion.matches=true; assert.deepEqual(sample(2),['0:0']);sample(8.1);assert.equal(playerFrame(state).frame,1);
`, sandbox);
console.log('Passed: locomotion clips and controls, 10-second sit threshold, held seated pose, immediate wake on movement/interaction, no sit while pressing into a wall, reduced-motion idle/sit.');
