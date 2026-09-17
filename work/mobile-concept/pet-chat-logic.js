 function renderChat(){
  q('#pm-no-chat').hidden=active!==null;
  q('#pm-has-chat').hidden=active===null;
  if(active===null)return;
  const d=dogs[active],s=sessions[active],latest=s.messages.at(-1);
  const pet=q('#pm-chat-avatar');
  if(pet.dataset.dog!==String(active)){
   q('#pm-earlier').open=false;
   q('#pm-message').value='';
   pet.dataset.dog=String(active);
  }
  pet.src=sprites[active];
  pet.alt=d.name+'의 털색과 귀 모양을 반영한 도트 캐릭터';
  text('#pm-stage-name',d.name);
  text('#pc-title',d.name+'랑 이야기');
  text('#pc-answer',latest?.answer||d.greeting);
  text('#pc-last-question',latest?'나 · '+latest.question:'');
  q('#pc-last-question').hidden=!latest;
  const save=q('#pc-save-question'),saved=!!latest&&s.pending.includes(latest.question);
  save.hidden=!latest?.needsCheck;
  save.disabled=saved;
  save.textContent=saved?'상담 질문에 담았어 ✓':'보호소에 확인할 질문으로 담기';
  q('#pm-earlier').hidden=s.messages.length===0;
  text('#pc-history-count',s.messages.length+'개 이야기 · '+(q('#pm-earlier').open?'접기':'펼치기'));
  q('#pm-earlier-log').replaceChildren(...s.messages.flatMap(messageNodes));
  q('#pm-reveal-invite').hidden=!s.ready;
  text('#pc-photo-label',s.revealed?'내 사진 다시 보기':'사진으로 만나기');
  refreshIcons();
 }
 function setupPetChat(){
  q('#pc-back').addEventListener('click',()=>q('#pm-back').click());
  q('#pc-save-question').addEventListener('click',()=>{
   if(active===null)return;
   const s=sessions[active],latest=s.messages.at(-1);
   if(!latest?.needsCheck)return;
   if(!s.pending.includes(latest.question))s.pending.push(latest.question);
   renderChat();
  });
  q('#pm-earlier').addEventListener('toggle',()=>{
   if(active!==null)text('#pc-history-count',sessions[active].messages.length+'개 이야기 · '+(q('#pm-earlier').open?'접기':'펼치기'));
  });
  const toyDesign={caseColor:'lemon'};
  const applyToyDesign=()=>{
   const colors={lemon:['#ffdb76','#ffe798','#efbc56'],strawberry:['#ffc9cd','#ffe0dc','#eea2ab'],mint:['#bde3c7','#dbf2d8','#9ec8ab']}[toyDesign.caseColor];
   ['--pc-case','--pc-case-light','--pc-case-shadow'].forEach((key,i)=>q('#pm-has-chat').style.setProperty(key,colors[i]));
  };
  if(globalThis.Tweak){
   const toyTweak=new Tweak({container:q('#pm-has-chat'),onChange:applyToyDesign});
   toyTweak.addSelect(toyDesign,'caseColor',{label:'게임기 색상',options:[{label:'레몬 옐로',value:'lemon'},{label:'딸기 우유',value:'strawberry'},{label:'민트 크림',value:'mint'}]});
  }
 }
