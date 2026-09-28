'use strict';
const $ = id => document.getElementById(id);
let config, token = null, account = null, session = null, note = null, selectedDog = null;
let busy = false, ended = false, pendingMessage = null, lastMessage = null, results = [];
const controls = ['run','cleanup','login-a','login-b','logout','reload-shelters','send','reload-chat','photos','save-note','reload-note'];
function notice(message, error = false) { $('notice').textContent = message; $('notice').classList.toggle('error', error); }
function controlsState() {
  for (const id of controls) $(id).disabled = !config || config.state !== 'READY' || busy || ended;
  for (const id of ['logout','send','reload-chat','photos','save-note','reload-note']) $(id).disabled ||= !token;
  for (const id of ['send','photos','save-note','reload-note']) $(id).disabled ||= !selectedDog;
  $('send').disabled ||= !config?.paidAi;
  $('reload-chat').disabled ||= !session;
  $('run').disabled ||= !config?.paidAi || results.length > 0;
  for (const button of document.querySelectorAll('.choices button')) button.disabled = busy || ended || button.dataset.readonly === 'true';
}
function resetPrivate() {
  token = null; account = null; session = null; note = null; selectedDog = null; pendingMessage = null; lastMessage = null;
  $('account-label').textContent = '로그인 전'; $('messages').replaceChildren();
  $('photo-panel').replaceChildren(); $('note-status').textContent = '로그인한 계정의 메모를 불러와 주세요.';
  $('note-questions').value = ''; $('care-plan').value = ''; $('household').checked = false;
  $('dogs').replaceChildren(); $('traits').replaceChildren(); $('dog-name').textContent = '봄이를 만나볼까요?';
  $('dog-intro').textContent = '아직 강아지를 선택하지 않았어요.'; $('behavior').textContent = '';
}
async function request(path, {method='GET', body, auth=token, local=false} = {}) {
  const headers = {'X-Local-Key': config.key};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && !local) headers.Authorization = `Bearer ${auth}`;
  const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 90000);
  try {
    const response = await fetch(local ? path : '/proxy'+path, {method, headers,
      body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal, cache:'no-store'});
    const data = await response.json();
    if (!local) {
      const item = document.createElement('li');
      item.textContent = `${response.status} ${method} ${path} · ${response.headers.get('X-Request-ID') || 'local'}`;
      $('requests').prepend(item);
    }
    return {status:response.status, data, retryAfter:response.headers.get('Retry-After')};
  } finally { clearTimeout(timeout); }
}
function expect(response, statuses) {
  if (!statuses.includes(response.status)) {
    const suffix = response.status === 429 ? ` ${response.retryAfter || '?'}초 뒤 다시 확인해 주세요.` : '';
    throw new Error(`${response.status} · ${response.data.code || ''} ${response.data.message || '요청을 완료하지 못했어요.'}${suffix}`);
  }
  return response.data;
}
function assert(value, message) { if (!value) throw new Error(message); }
async function action(fn) {
  if (busy || ended) return;
  busy = true; controlsState();
  try { await fn(); } catch (e) { notice(e.name === 'AbortError' ? '응답을 기다리는 시간이 길어졌어요. 새 요청을 만들기 전에 저장된 기록을 확인해 주세요.' : e.message, true); }
  finally { busy = false; controlsState(); }
}
async function login(label) {
  resetPrivate();
  const auth = expect(await request('/local/login',{method:'POST',local:true,body:{account:label}}),[200]);
  token = auth.accessToken; account = label;
  const me = expect(await request('/v1/me',{method:'POST'}),[200]).data;
  assert(me.role === 'USER','임시 계정에 일반 사용자 외의 역할이 있어요.');
  $('account-label').textContent = `임시 계정 ${label}`;
  notice(`계정 ${label}로 실제 Supabase 로그인과 사용자 등록을 마쳤어요.`);
  return me;
}
function choice(container, label, handler) {
  const button = document.createElement('button'); button.textContent=label;
  button.addEventListener('click',()=>action(handler)); container.append(button); return button;
}
async function shelters() {
  const rows=expect(await request('/v1/shelters'),[200]).data;
  $('shelters').replaceChildren();
  for (const row of rows) choice($('shelters'),row.name,()=>dogs(row.id));
  return rows;
}
async function dogs(shelterId) {
  const rows=expect(await request(`/v1/shelters/${shelterId}/dogs`),[200]).data;
  $('dogs').replaceChildren();
  for (const row of rows) {
    const button=choice($('dogs'),row.name,()=>dog(row.id));
    // Writes are restricted by the local adapter to the fictional Bomi fixture.
    if (row.id !== config.dogId) { button.disabled=true; button.title='이번 저장 검증은 가상 봄이를 사용해요.'; button.dataset.readonly='true'; }
  }
  return rows;
}
async function dog(id) {
  const row=expect(await request(`/v1/dogs/${id}`),[200]).data;
  selectedDog=row; session=null; note=null; pendingMessage=null; lastMessage=null;
  $('dog-name').textContent=row.name; $('dog-intro').textContent=row.introduction || '보호소에서 확인한 성격을 알아봐요.';
  $('traits').replaceChildren();
  for (const trait of row.traitLabels || []) {const tag=document.createElement('span');tag.textContent=trait;$('traits').append(tag);}
  const behavior=expect(await request(`/v1/dogs/${id}/behavior`),[200]).data;
  const actions=Object.entries(behavior.settings.actions).filter(([,v])=>v.weight>0).map(([key])=>key);
  $('behavior').textContent=`서버 행동 설정 · ${actions.join(' / ')}`;
  return row;
}
async function openChat() {
  session=expect(await request(`/v1/dogs/${selectedDog.id}/chat-sessions`,{method:'POST'}),[200,201]).data;
  return session;
}
async function loadChat() {
  if (!session) await openChat();
  const rows=expect(await request(`/v1/chat-sessions/${session.id}/messages?limit=50`),[200]).data;
  $('messages').replaceChildren();
  for (const row of rows) {const bubble=document.createElement('div');bubble.className='bubble'+(row.role==='USER'?' user':'');bubble.textContent=row.text;$('messages').append(bubble);}
  return rows;
}
async function saveMessage(text) {
  if (!session) await openChat();
  if (!pendingMessage || pendingMessage.text !== text) pendingMessage={clientMessageId:crypto.randomUUID(),text};
  lastMessage=expect(await request(`/v1/chat-sessions/${session.id}/messages`,{method:'POST',body:pendingMessage}),[200,201]).data;
  await loadChat(); return lastMessage;
}
async function reply() {
  const response=expect(await request(`/v1/chat-sessions/${session.id}/messages/${lastMessage.id}/reply`,{method:'POST',body:{}}),[200,201,202]).data;
  if (response.processingStatus !== 'COMPLETED' || !response.reply) {
    throw new Error(`답변 ${response.processingStatus} · ${response.failureCode || '생성 중이에요. 저장된 대화를 나중에 확인해 주세요.'}`);
  }
  await loadChat(); pendingMessage=null; return response;
}
async function photos() {
  const response=await request(`/v1/dogs/${selectedDog.id}/photos`);
  $('photo-panel').replaceChildren();
  if (response.status===403 && response.data.code==='PHOTO_LOCKED') {
    const p=document.createElement('p');p.textContent='아직 사진이 잠겨 있어요. 먼저 대화를 나눠 주세요.';$('photo-panel').append(p);return response;
  }
  const data=expect(response,[200]).data;
  const photo=data.find(item=>item.id===config.photoId);
  if (!photo) {const p=document.createElement('p');p.textContent='표시할 검증 이미지가 없어요.';$('photo-panel').append(p);return response;}
  const url=new URL(photo.url);
  assert(url.protocol==='https:' && url.hostname.endsWith('.supabase.co'),'사진 주소를 확인해 주세요.');
  const image=document.createElement('img');image.alt='직접 만든 체크무늬 저장소 검증 이미지';image.referrerPolicy='no-referrer';image.src=photo.url;
  $('photo-panel').append(image);await image.decode();assert(image.naturalWidth===8 && image.naturalHeight===8,'검증 이미지가 다르게 표시돼요.');
  const caption=document.createElement('p');caption.textContent='대화 후 공개 · 서명 URL 이미지 표시 성공';$('photo-panel').append(caption);
  return response;
}
function noteInput(version=note?.updatedAt ?? null) {return {questions:$('note-questions').value,carePlan:$('care-plan').value,
  checklist:{householdDiscussed:$('household').checked},expectedUpdatedAt:version};}
function showNote(value) {
  note=value;$('note-questions').value=value?.questions || '';$('care-plan').value=value?.carePlan || '';
  $('household').checked=Boolean(value?.checklist?.householdDiscussed);
  $('note-status').textContent=value?'서버에 저장된 메모를 확인했어요.':'이 계정에는 아직 저장된 메모가 없어요.';
}
async function readNote() {
  const response=await request(`/v1/me/adoption-notes/${selectedDog.id}`);
  if (response.status===404 && response.data.code==='NOTE_NOT_FOUND') {showNote(null);return null;}
  const value=expect(response,[200]).data;showNote(value);return value;
}
async function saveNote() {const value=expect(await request(`/v1/me/adoption-notes/${selectedDog.id}`,{method:'PUT',body:noteInput()}),[200,201]).data;showNote(value);return value;}
async function step(title,fn) {
  $('progress').textContent=`${results.length}개 통과 · ${title} 확인 중…`;
  const item=document.createElement('li'),dot=document.createElement('span'),content=document.createElement('div'),detail=document.createElement('small');
  dot.className='result-dot';dot.textContent='·';content.textContent=title;detail.className='check-detail';content.append(detail);item.append(dot,content);$('checks').append(item);
  try {const text=await fn();dot.textContent='✓';detail.textContent=text;results.push({title,result:'PASS',detail:text});}
  catch(e){dot.textContent='!';item.className='failed';detail.textContent=e.message;results.push({title,result:'FAIL',detail:e.message});throw e;}
}
async function runFlow() {
  let meA, savedSession, savedText, savedVersion, answer;
  await step('로그인 전 접근 차단',async()=>{const r=await request('/v1/me',{auth:null});assert(r.status===401,'비로그인 요청이 차단되지 않았어요.');return '실제 API 401 확인';});
  await step('Supabase 로그인 · 사용자 등록',async()=>{meA=await login('A');return '임시 계정 A · USER 역할 확인';});
  await step('보호소 · 강아지 · 행동 설정',async()=>{const rows=await shelters();const home=rows.find(r=>r.id==='02100000-0000-4000-8000-000000000001');assert(home,'가상 보호소가 없어요.');const list=await dogs(home.id);assert(list.some(d=>d.id===config.dogId),'봄이가 없어요.');await dog(config.dogId);return `${rows.length}개 보호소 · 봄이 프로필과 행동 조회`;});
  await step('대화 전 사진 잠금',async()=>{const r=await photos();assert(r.status===403 && r.data.code==='PHOTO_LOCKED','사진 잠금이 맞지 않아요.');return '403 PHOTO_LOCKED';});
  await step('대화방 재사용',async()=>{await openChat();savedSession=session.id;await openChat();assert(session.id===savedSession,'대화방이 중복 생성됐어요.');return '같은 강아지의 열린 대화방 유지';});
  await step('메시지 저장 · 중복 방지',async()=>{await saveMessage('낯선 사람이 다가오면 어떻게 해?');const path=`/v1/chat-sessions/${session.id}/messages`;const duplicate=expect(await request(path,{method:'POST',body:pendingMessage}),[200]).data;assert(duplicate.id===lastMessage.id,'중복 메시지가 생겼어요.');const conflict=await request(path,{method:'POST',body:{...pendingMessage,text:'다른 내용'}});assert(conflict.status===409 && conflict.data.code==='MESSAGE_ID_CONFLICT','메시지 충돌이 차단되지 않았어요.');return '동일 요청 재사용 · 다른 내용 409';});
  await step('기록 기반 AI 답변 · 화면 표시',async()=>{answer=await reply();assert(answer.reply.text.length>0,'답변이 비어 있어요.');return answer.reply.needsShelterConfirmation?'보호소 확인 안내 표시':'COMPLETED · 답변 저장과 재조회';});
  await step('대화 후 사진 공개 · 이미지 표시',async()=>{const r=await photos();assert(r.status===200 && r.data.data.some(p=>p.id===config.photoId),'검증 사진이 없어요.');return '60초 서명 URL · 8×8 검증 이미지 로딩';});
  await step('입양 메모 저장 · 재조회',async()=>{await readNote();$('note-questions').value='봄이가 좋아하는 산책 시간은 언제인가요?';$('care-plan').value='아침과 저녁에 짧게 산책하고, 적응할 시간을 충분히 주기.';$('household').checked=true;await saveNote();savedText=note.questions;savedVersion=note.updatedAt;const got=await readNote();assert(got.questions===savedText && got.checklist.householdDiscussed,'저장한 메모가 달라요.');return '개인 질문·돌봄 계획·체크 항목 저장';});
  await step('오래된 메모 덮어쓰기 차단',async()=>{$('note-questions').value=savedText+' 좋아하는 간식도 확인하기.';await saveNote();savedText=note.questions;const r=await request(`/v1/me/adoption-notes/${config.dogId}`,{method:'PUT',body:noteInput(savedVersion)});assert(r.status===409 && r.data.code==='NOTE_VERSION_CONFLICT','오래된 수정이 차단되지 않았어요.');return '409 NOTE_VERSION_CONFLICT · 최신 내용 보존';});
  await step('계정 B에서 A의 기록 접근 차단',async()=>{await login('B');await dog(config.dogId);const r=await request(`/v1/chat-sessions/${savedSession}`);assert(r.status===404,'다른 계정의 대화방이 노출됐어요.');assert(await readNote()===null,'다른 계정 메모가 노출됐어요.');const p=await photos();assert(p.status===403,'계정별 사진 조건이 분리되지 않았어요.');return '다른 대화방 404 · 개인 메모 없음 · 사진 잠금';});
  await step('다시 로그인해도 기록 유지',async()=>{const a=await login('A');assert(a.id===meA.id,'같은 계정의 ID가 바뀌었어요.');await dog(config.dogId);await openChat();assert(session.id===savedSession,'이전 대화방이 사라졌어요.');const messages=await loadChat();assert(messages.some(m=>m.id===answer.reply.id),'이전 AI 답변이 사라졌어요.');await photos();const got=await readNote();assert(got.questions===savedText,'이전 메모가 사라졌어요.');return '새 로그인 토큰으로 동일 대화·답변·메모 확인';});
  await step('로그아웃 상태의 보호 요청 차단',async()=>{const r=await request('/v1/me',{auth:null});assert(r.status===401,'토큰 없는 요청이 차단되지 않았어요.');return '로그인하지 않은 요청 401 · 서버 데이터 유지';});
  $('progress').textContent=`${results.length}개 흐름 검증 통과`;
  notice('화면에서 실제 서버 연결을 확인했어요.\n아래 내용을 살펴본 뒤 “임시 데이터 정리”를 누르면 이번 계정과 자료만 정리돼요.');
}
async function finishFlow() {
  expect(await request('/local/finish',{method:'POST',local:true}),[202]);ended=true;token=null;
  for(let i=0;i<90;i++) {
    await new Promise(resolve=>setTimeout(resolve,1000));
    const r=await fetch('/local/config',{cache:'no-store'});config=await r.json();
    if(config.state==='CLEANED') {
      $('server-status').textContent='검증 완료 · 자료 정리됨';
      notice(`임시 계정·대화·메모·검증 이미지를 정리했어요.\n기존 12개 테이블의 건수를 확인했어요. 실제 AI 생성 기록 ${config.generatedReplies}건.\n다시 실행하려면 로컬 실행 도구를 새로 시작해 주세요.`);return;
    }
    if(config.state==='FAILED') throw new Error('정리 완료를 확인하지 못했어요. 로컬 복구 기록을 확인해 주세요.');
  }
  throw new Error('정리 결과를 기다리고 있어요. 화면을 다시 열어 상태를 확인해 주세요.');
}
$('run').addEventListener('click',()=>action(runFlow));
$('cleanup').addEventListener('click',()=>action(finishFlow));
$('login-a').addEventListener('click',()=>action(async()=>{await login('A');await shelters();}));
$('login-b').addEventListener('click',()=>action(async()=>{await login('B');await shelters();}));
$('logout').addEventListener('click',()=>action(async()=>{resetPrivate();const r=await request('/v1/me',{auth:null});expect(r,[401]);notice('화면의 로그인 정보와 개인 기록을 비웠어요. 비로그인 요청 401을 확인했어요.');}));
$('reload-shelters').addEventListener('click',()=>action(shelters));
$('send').addEventListener('click',()=>action(async()=>{const text=$('question').value.trim();assert(text,'질문을 입력해 주세요.');await saveMessage(text);await reply();notice('답변을 서버에 저장하고 다시 불러왔어요.');}));
$('reload-chat').addEventListener('click',()=>action(loadChat));
$('photos').addEventListener('click',()=>action(photos));
$('save-note').addEventListener('click',()=>action(saveNote));
$('reload-note').addEventListener('click',()=>action(readNote));
async function initialize() {
  try {
    config=await (await fetch('/local/config',{cache:'no-store'})).json();
    $('origin').textContent=config.apiOrigin;
    $('scope').textContent=config.paidAi?'전체 검증은 실제 AI 답변 1회를 요청해요. 사진은 직접 만든 검증 이미지예요.':'조회·저장 수동 검증 모드예요. 실제 AI 답변은 실행 옵션으로 켤 수 있어요.';
    if(config.state==='PREPARING'){setTimeout(initialize,1500);return;}
    if(config.state!=='READY'){ended=true;$('server-status').textContent=config.state==='CLEANED'?'자료 정리 완료':'검증 세션 종료';notice('이 세션은 종료됐어요. 로컬 실행 도구를 새로 시작해 주세요.');return;}
    $('server-status').textContent='실제 개발 서버 연결';notice('준비됐어요. 전체 흐름 검증 또는 계정 A 로그인을 눌러 주세요.');controlsState();
  } catch(e){notice('로컬 연결 도구를 찾을 수 없어요. 실행 상태를 확인해 주세요.',true);}
}
initialize();
