from pathlib import Path
import argparse,re,base64
b=Path(__file__).parent
parser=argparse.ArgumentParser(description='Build the shelter mobile prototype fragment.')
parser.add_argument('--output',type=Path,default=b.parents[1]/'outputs'/'joystick-shelter-mobile.html')
args=parser.parse_args()
s=(b/'shelter-mobile.html').read_text()
s=s.replace('</style>',(b/'walk.css').read_text()+'\n'+(b/'pet-chat.css').read_text()+'\n</style>',1)
s,count=re.subn(r'    <section data-screen="chat".*?(?=    <section data-screen="photo")',(b/'pet-chat-section.html').read_text()+'\n',s,count=1,flags=re.S)
assert count==1, 'Expected one conversation section'
s,count=re.subn(r' function renderChat\(\)\{.*?\n(?= function topicFor)',lambda m:(b/'pet-chat-logic.js').read_text()+'\n',s,count=1,flags=re.S)
assert count==1, 'Expected one conversation renderer'
s=s.replace("if(e.key==='Enter'){e.preventDefault();ask(q('#pm-message').value);}","if(e.key==='Enter'&&!e.isComposing){e.preventDefault();ask(q('#pm-message').value);}",1)
s=s.replace('  <main class="pm-content">','  <main class="pm-content">\n'+(b/'walk-section.html').read_text(),1)
s=s.replace('<div class="ps-friends-heading">',(b/'walk-enter.html').read_text()+'\n<div class="ps-friends-heading">',1)
s=s.replace(" function show(view){"," function show(view){\n  if(screenName==='walk')stopWalking();\n  if(view==='walk'&&!shelterById(selectedShelterId)?.connected)view='home';",1)
s=s.replace("const navView=view==='shelter'||view==='location'?'home'", "const navView=view==='shelter'||view==='location'||view==='walk'?'home'",1)
s=s.replace("q('#pm-back').hidden=atStart;", "q('#pm-back').hidden=atStart||view==='walk'||view==='photo'||(view==='chat'&&active!==null);",1)
s=s.replace("screenName=view;qa('[data-screen]')", "screenName=view;root.dataset.view=view;root.dataset.chatActive=String(view==='chat'&&active!==null);qa('[data-screen]')",1)
s=s.replace("q('.pm-bottom').hidden=accountPreview.mode!=='adopter';", "q('.pm-bottom').hidden=view==='walk'||(view==='chat'&&active!==null)||accountPreview.mode!=='adopter';q('.pm-homebar').hidden=view==='walk'||(view==='chat'&&active!==null);",1)
s=s.replace("if(view==='home')renderNearby();", "if(view==='home')renderNearby();\n  if(view==='walk')enterWalk();",1)
s=s.replace("const entries=dogs.map((d,i)=>({d,i})).filter(({d})=>d.shelterId===s.id);", "const entries=dogs.map((d,i)=>({d,i})).filter(({d})=>d.shelterId===s.id);q('#pw-enter').hidden=entries.length===0;",1)
s=s.replace("if(screenName==='chat'&&active!==null){selectedShelterId=dogs[active].shelterId;show('shelter');return;}", "if(screenName==='chat'&&active!==null){selectedShelterId=dogs[active].shelterId;show(walkFromShelter===selectedShelterId?'walk':'shelter');return;}",1)
logic=(b/'walk-logic.js').read_text().replace('__NATURAL_DOG_LOGIC__',(b/'natural-dogs.js').read_text()).replace('__BALL_PLAY_LOGIC__',(b/'ball-play.js').read_text())
s=s.replace(' setupShelters();setupProfile();renderHome();',logic+"\n setupWalk();setupPetChat();setupShelters();setupProfile();renderHome();")
s=s.replace("}else show('home');refreshIcons();", "}else {selectedShelterId='ongi';active=3;walkFromShelter=selectedShelterId;show('chat');}refreshIcons();",1)
s=s.replace("tweak.addSlider(design,'shadow'", "tweak.addSlider(walkSettings,'speed',{label:'걷는 속도',min:35,max:75,step:5});tweak.addSlider(design,'shadow'",1)
(b/'joystick-mobile.html').write_text(s)
files={'BORI':'bori-avatar.png','DUBU':'dubu-avatar.png','BAMI':'bami-avatar.png','GARDEN':'garden-world.png','WALKER':'player-walk-atlas.png','DOGWALKS':'dog-walk-atlas.png','CHATROOM':'pet-chat-room.png'}
for key,file in files.items():s=s.replace('__'+key+'__','data:image/png;base64,'+base64.b64encode((b/file).read_bytes()).decode())
s=s.replace('puppy-first-mobile','joystick-shelter-mobile').replace('도트 강아지와 대화하고 사진을 만나는 모바일 앱 시안','조이스틱으로 도트 보호소를 걸으며 강아지를 만나는 조작 시안')
out=args.output
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(s)
(b/'walk-check.js').write_text(re.search(r'<script>(.*?)</script>',s,re.S).group(1))
print('Joystick prototype:',len(s.encode()),'bytes')
