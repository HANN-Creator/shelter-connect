"""Build and verify deployment data. No commands supplied by a manifest are executed."""
import argparse,hashlib,json,os,re,stat,zipfile
from pathlib import Path,PurePosixPath

REPOSITORY='HANN-Creator/shelter-connect'
CAPABILITY='windows-local-storage-v1'
REQUIRED_CLASSES={'BOOT-INF/classes/org/shelterconnect/api/storage/LocalObjectStore.class',
 'BOOT-INF/classes/org/shelterconnect/api/photo/LocalPhotoStorage.class',
 'BOOT-INF/classes/org/shelterconnect/api/asset/LocalAssetStorage.class',
 'BOOT-INF/classes/org/shelterconnect/api/community/LocalCommunityStorage.class'}

def sha(data):return hashlib.sha256(data).hexdigest()

def requirement_pins(text):
    result={}
    for raw in text.splitlines():
        line=raw.split('#',1)[0].strip()
        if not line:continue
        match=re.fullmatch(r'([A-Za-z0-9][A-Za-z0-9_.-]*)\s*==\s*([A-Za-z0-9][A-Za-z0-9.+!-]*)',line)
        if not match:raise ValueError('Deployment requirements must contain exact package pins only.')
        name=re.sub(r'[-_.]+','-',match[1]).lower()
        if name in result:raise ValueError('Duplicate dependency pin refused.')
        result[name]=match[2]
    if not result:raise ValueError('Dependency pins missing.')
    return result

def require_dependencies(current,candidate,installed_version):
    expected=requirement_pins(candidate)
    if requirement_pins(current)!=expected:raise ValueError('Python dependency pins changed; install and verify separately before deployment.')
    for name,version in expected.items():
        if installed_version(name)!=version:raise ValueError('Installed Python dependency does not match the release pin: '+name)

def migrations(jar):
    with zipfile.ZipFile(jar) as z:
        if not REQUIRED_CLASSES.issubset(z.namelist()):raise ValueError('Local storage capability missing from JAR.')
        result={n:sha(z.read(n)) for n in z.namelist() if n.startswith('BOOT-INF/classes/db/migration/') and n.endswith('.sql')}
        if not result:raise ValueError('Migration inventory missing.')
        return result

def safe_name(name):
    p=PurePosixPath(name)
    if '\\' in name or ':' in name or p.is_absolute() or any(v in ('','..','.') for v in name.split('/')):raise ValueError('Unsafe archive path.')
    if any(v.endswith((' ','.')) or v.split('.')[0].upper() in {'CON','PRN','AUX','NUL',*[f'COM{i}' for i in range(1,10)],*[f'LPT{i}' for i in range(1,10)]} for v in p.parts):raise ValueError('Windows special path refused.')
    return p

def unpack(path,target):
    target=Path(target)
    if target.exists():raise ValueError('Extraction target already exists.')
    with zipfile.ZipFile(path) as z:
        names=set();size=0
        for item in z.infolist():
            name=item.filename;safe_name(name)
            if name.casefold() in names or stat.S_ISLNK(item.external_attr>>16) or item.is_dir():raise ValueError('Duplicate, directory or link entry refused.')
            names.add(name.casefold());size+=item.file_size
            if size>512*1024*1024 or len(names)>20000:raise ValueError('Release exceeds extraction bounds.')
        if 'release-manifest.json' not in z.namelist():raise ValueError('Release manifest missing.')
        m=json.loads(z.read('release-manifest.json'))
        if m.get('repository')!=REPOSITORY or m.get('capability')!=CAPABILITY or not re.fullmatch('[a-f0-9]{40}',m.get('commit','')):raise ValueError('Release identity invalid.')
        if set(z.namelist())!=set(m['files'])|{'release-manifest.json'}:raise ValueError('Manifest file inventory differs.')
        for name,expected in m['files'].items():
            if not name.startswith('app/'):raise ValueError('Only app files may be deployed.')
            data=z.read(name)
            if len(data)!=expected['bytes'] or sha(data)!=expected['sha256']:raise ValueError('Release file checksum mismatch.')
        target.mkdir(parents=True)
        for name in z.namelist():
            out=target/name;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.read(name))
    if migrations(target/'app/shelter-connect-api.jar')!=m['migrations']:raise ValueError('Packaged JAR migration inventory differs.')
    return m

def require_tested_run(run,manifest):
    if run.get('conclusion')!='success' or run.get('event')!='push' or run.get('head_branch')!='main' or run.get('head_sha')!=manifest['commit'] or run.get('path')!='.github/workflows/backend.yml' or run.get('repository',{}).get('full_name')!=REPOSITORY:raise ValueError('Backend test run provenance mismatch.')

def build(backend,output):
    backend=Path(backend).resolve();jar=backend/'build/libs/shelter-connect-api.jar'
    m={'format':1,'repository':REPOSITORY,'capability':CAPABILITY,'commit':os.environ['SOURCE_SHA'],
       'test_run_id':int(os.environ['TEST_RUN_ID']),'release_run_id':int(os.environ['RELEASE_RUN_ID']),
       'migrations':migrations(jar),'files':{}}
    if not re.fullmatch('[a-f0-9]{40}',m['commit']):raise ValueError('Invalid source commit.')
    client=(backend/'scripts/styled_dog/client.py').read_text(encoding='utf-8')
    if 'def job_lock(' not in client or 'msvcrt.locking' not in client:raise ValueError('Windows paid-job locking prerequisite missing.')
    files={'app/shelter-connect-api.jar':jar,'app/motion-requirements.txt':backend/'motion-requirements.txt'}
    for folder in ('scripts','asset-styles'):
        for p in (backend/folder).rglob('*'):
            if p.is_file() and not p.is_symlink() and '__pycache__' not in p.parts and p.suffix!='.pyc':files['app/'+p.relative_to(backend).as_posix()]=p
    output=Path(output);output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
        for name,path in sorted(files.items()):
            safe_name(name);data=path.read_bytes();m['files'][name]={'bytes':len(data),'sha256':sha(data)};z.writestr(name,data)
        z.writestr('release-manifest.json',json.dumps(m,sort_keys=True))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['build']);parser.add_argument('--backend',required=True);parser.add_argument('--output',required=True);args=parser.parse_args();build(args.backend,args.output)
