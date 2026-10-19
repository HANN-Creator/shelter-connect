"""Windows scheduled pull deployment. GitHub token never leaves api.github.com."""
import argparse,hashlib,json,os,subprocess,sys,time,urllib.request,urllib.error,urllib.parse,uuid,zipfile
from importlib.metadata import version as installed_version
from datetime import datetime
from pathlib import Path
from backup_security import current_sid,read_json
from auto_migrate import plan as migration_plan,Migration
from release_bundle import REPOSITORY,unpack,migrations,require_tested_run,require_dependencies

HOME=Path(r'C:\ProgramData\ShelterConnectDeploy')
APP_ROOT=Path(r'C:\ProgramData\ShelterConnectPreview')
BACKUP_HOME=Path(r'C:\ProgramData\ShelterConnectBackup')
API='https://api.github.com/repos/'+REPOSITORY

def available_memory():
    import ctypes
    from ctypes import wintypes
    class Performance(ctypes.Structure):
        _fields_=[('cb',wintypes.DWORD)]+[(n,ctypes.c_size_t) for n in ('CommitTotal','CommitLimit','CommitPeak','PhysicalTotal','PhysicalAvailable','SystemCache','KernelTotal','KernelPaged','KernelNonpaged','PageSize')]+[(n,wintypes.DWORD) for n in ('HandleCount','ProcessCount','ThreadCount')]
    p=Performance();p.cb=ctypes.sizeof(p)
    if not ctypes.windll.psapi.GetPerformanceInfo(ctypes.byref(p),p.cb):raise RuntimeError('Cannot check deployment memory headroom.')
    return min((p.CommitLimit-p.CommitTotal)*p.PageSize,p.PhysicalAvailable*p.PageSize)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def api(config,path):
    if not path.startswith('/') or path.startswith('//'):raise RuntimeError('Unexpected GitHub API path.')
    request=urllib.request.Request(API+path,headers={'Authorization':'Bearer '+config['github_read_token'],'Accept':'application/vnd.github+json','User-Agent':'ShelterConnect-PullDeploy/1.0','X-GitHub-Api-Version':'2022-11-28'})
    try:
        with urllib.request.urlopen(request,timeout=30) as response:return json.loads(response.read(8*1024*1024))
    except urllib.error.HTTPError as error:
        code=error.code;error.close();raise RuntimeError('GitHub API HTTP '+str(code)+'; check read-only token expiry and repository Actions access.') from None

def download(config,artifact,path):
    request=urllib.request.Request(API+'/actions/artifacts/'+str(int(artifact['id']))+'/zip',headers={'Authorization':'Bearer '+config['github_read_token'],'User-Agent':'ShelterConnect-PullDeploy/1.0'})
    opener=urllib.request.build_opener(NoRedirect())
    try:
        response=opener.open(request,timeout=30);response.close();raise RuntimeError('Artifact API did not return the expected signed redirect.')
    except urllib.error.HTTPError as error:
        if error.code!=302:raise RuntimeError('Artifact download authorization failed.') from None
        location=error.headers.get('Location','');error.close()
    parsed=urllib.parse.urlsplit(location)
    if parsed.scheme!='https' or not parsed.hostname or parsed.username or parsed.password or not parsed.hostname.endswith(('.blob.core.windows.net','.actions.githubusercontent.com','.githubusercontent.com')):raise RuntimeError('Unexpected artifact download host.')
    # Fresh request deliberately has NO Authorization header.
    with opener.open(urllib.request.Request(location,headers={'User-Agent':'ShelterConnect-PullDeploy/1.0'}),timeout=60) as response,path.open('wb') as output:
        total=0
        while chunk:=response.read(1024*1024):
            total+=len(chunk)
            if total>256*1024*1024:raise RuntimeError('Artifact download exceeds size limit.')
            output.write(chunk)
    expected=artifact.get('digest','')
    with path.open('rb') as stream:actual='sha256:'+hashlib.file_digest(stream,'sha256').hexdigest()
    if not expected.startswith('sha256:') or actual!=expected:raise RuntimeError('GitHub artifact digest mismatch.')

def command(args,timeout=300):
    result=subprocess.run([str(a) for a in args],capture_output=True,timeout=timeout)
    if result.returncode:raise RuntimeError('Local deployment command failed; inspect protected service logs.')

def service(action,name):
    if action not in ('Start','Stop') or name not in ('ShelterConnectPreviewBackend','ShelterConnectTunnel'):raise ValueError('Unexpected service operation.')
    command(['powershell.exe','-NoProfile','-NonInteractive','-Command',action+"-Service -Name '"+name+"' -ErrorAction Stop"],120)

def health(url):
    for _ in range(45):
        try:
            request=urllib.request.Request(url,headers={'User-Agent':'ShelterConnect-PullDeploy/1.0'})
            with urllib.request.urlopen(request,timeout=3) as response:
                if response.status==200 and json.loads(response.read(8192)).get('status')=='UP':return
        except (OSError,ValueError):pass
        time.sleep(2)
    raise RuntimeError('Deployment readiness failed.')

def main(check_only=False):
    import msvcrt
    config=read_json(HOME/'config.dpapi')
    if config['owner_sid']!=current_sid():raise RuntimeError('Use the configured Windows deploy account.')
    if not config.get('enabled') and not check_only:print('AUTO_DEPLOY_DISABLED: configuration preserved.');return
    poll=(HOME/'poll.lock').open('a+b')
    if poll.seek(0,2)==0:poll.write(b'0');poll.flush()
    poll.seek(0)
    try:msvcrt.locking(poll.fileno(),msvcrt.LK_NBLCK,1)
    except OSError:print('Another deploy poll is active.');return
    state_path=HOME/'last-status.json';previous=json.loads(state_path.read_text()) if state_path.exists() else {}
    state={'phase':'checking','checked':datetime.now().isoformat(timespec='seconds')}
    def save():state_path.write_text(json.dumps(state,indent=2))
    save()
    try:
        if available_memory()<1024**3:
            state.update(phase='waiting_for_memory');save();print('WAITING_FOR_MEMORY: less than 1 GiB of RAM/commit headroom; services unchanged, next poll will retry.');return
        head=api(config,'/commits/main')['sha']
        if not check_only and (previous.get('deployed_commit')==head or previous.get('failed_commit')==head):
            state.update(previous,checked=state['checked']);save();return
        security=api(config,'/actions/workflows/dependency-security.yml/runs?branch=main&head_sha='+head+'&per_page=5')['workflow_runs']
        if not any(r.get('head_sha')==head and r.get('head_branch')=='main' and r.get('event')=='push' and r.get('conclusion')=='success' and r.get('path')=='.github/workflows/dependency-security.yml' and r.get('repository',{}).get('full_name')==REPOSITORY for r in security):
            state.update(phase='waiting_for_security_check',commit=head);save();print('WAITING_FOR_SECURITY_CHECK: production unchanged.');return
        runs=api(config,'/actions/workflows/mini-pc-release.yml/runs?branch=main&status=success&per_page=20')['workflow_runs']
        selected=None
        for run in runs:
            if run.get('event')!='workflow_run' or run.get('path')!='.github/workflows/mini-pc-release.yml' or run.get('repository',{}).get('full_name')!=REPOSITORY:continue
            artifacts=api(config,'/actions/runs/'+str(int(run['id']))+'/artifacts')['artifacts']
            for artifact in artifacts:
                if artifact['name']=='mini-pc-release-'+head and not artifact.get('expired'):
                    selected=(run,artifact);break
            if selected:break
        if selected is None:state.update(phase='waiting_for_tested_release',commit=head);save();print('WAITING_FOR_TESTED_RELEASE: production unchanged.');return
        run,artifact=selected;state['commit']=head
        releases=APP_ROOT/'releases';releases.mkdir(exist_ok=True)
        release=releases/(datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+head[:12]+'-'+uuid.uuid4().hex[:6]);release.mkdir()
        outer=release/'github-artifact.zip';download(config,artifact,outer)
        with zipfile.ZipFile(outer) as z:
            if z.namelist()!=['release.zip'] or z.getinfo('release.zip').file_size>256*1024*1024:raise RuntimeError('Unexpected outer artifact contents.')
            (release/'release.zip').write_bytes(z.read('release.zip'))
        candidate=release/'candidate';manifest=unpack(release/'release.zip',candidate)
        if manifest['commit']!=head or manifest['release_run_id']!=run['id']:raise RuntimeError('Artifact release run or commit mismatch.')
        test=api(config,'/actions/runs/'+str(int(manifest['test_run_id'])));require_tested_run(test,manifest)
        current=APP_ROOT/'app'
        pending=migration_plan(migrations(current/'shelter-connect-api.jar'),manifest['migrations'],candidate/'app/shelter-connect-api.jar')
        requirements=current/'motion-requirements.txt'
        if not requirements.exists():requirements=Path(config['workspace'])/'shelter-connect/backend/motion-requirements.txt'
        require_dependencies(requirements.read_text(encoding='utf-8-sig'),(candidate/'app/motion-requirements.txt').read_text(encoding='utf-8-sig'),installed_version)
        state.update(phase='release_verified',commit=head,release=str(release));save()
        if check_only:print('DEPLOY_CHECK_OK: trusted tested commit, checksums, storage capability and append-only migration plan and unchanged dependencies; no service switch.');return
        command([config['python'],Path(config['workspace'])/'outputs/production_backup.py'],1800)
        # Backup worker owns the same byte lock. Acquire only AFTER it completes.
        operation=(BACKUP_HOME/'operation.lock').open('r+b');operation.seek(0)
        try:msvcrt.locking(operation.fileno(),msvcrt.LK_NBLCK,1)
        except OSError:raise RuntimeError('Another backup/deployment is active; no services stopped.') from None
        moved=False;stopped=False;production_migration_attempted=False
        try:
            if api(config,'/commits/main')['sha']!=head:raise RuntimeError('Main advanced during preparation; production unchanged.')
            # Candidate must be readable/executable by the existing LocalService runtime.
            command(['icacls.exe',candidate/'app','/grant','*S-1-5-19:(OI)(CI)RX','/T','/Q'])
            migration=None
            if pending:
                state.update(phase='migration_rehearsal');save()
                migration=Migration(candidate/'app/shelter-connect-api.jar',release)
                migration.rehearse()
            state.update(phase='switching',failed_commit=head);save()
            stopped=True
            service('Stop','ShelterConnectTunnel');service('Stop','ShelterConnectPreviewBackend')
            if migration:
                state.update(phase='migrating',pending_migrations=pending);save()
                production_migration_attempted=True
                migration.production()
            current.replace(release/'previous-app');moved=True
            (candidate/'app').replace(current)
            service('Start','ShelterConnectPreviewBackend');health('http://127.0.0.1:8083/actuator/health/readiness')
            service('Start','ShelterConnectTunnel');health('https://api.shelterconnect.win/actuator/health/readiness')
            command([config['python'],Path(config['workspace'])/'outputs/verify_production_storage_operator.py'],120)
            state.update(phase='deployed',deployed_commit=head,completed=datetime.now().isoformat(timespec='seconds'),previous_app=str(release/'previous-app'));save()
            state.pop('failed_commit',None);save()
            print('AUTO_DEPLOY_OK: tested main deployed; backup, readiness and signed photo downloads verified.')
        except Exception:
            if production_migration_attempted:
                # No automatic restore of an old DB or old code after schema changes.
                for name in ('ShelterConnectTunnel','ShelterConnectPreviewBackend'):
                    try:service('Stop',name)
                    except Exception:pass
                state.update(phase='migration_recovery_required',failed_commit=head);save()
            elif stopped:
                for name in ('ShelterConnectTunnel','ShelterConnectPreviewBackend'):
                    try:service('Stop',name)
                    except Exception:pass
                if moved:
                    if current.exists():current.replace(release/'failed-app')
                    (release/'previous-app').replace(current)
                service('Start','ShelterConnectPreviewBackend');health('http://127.0.0.1:8083/actuator/health/readiness');service('Start','ShelterConnectTunnel')
                state.update(phase='rolled_back',failed_commit=head,completed=datetime.now().isoformat(timespec='seconds'));save()
            raise
        finally:
            operation.seek(0);msvcrt.locking(operation.fileno(),msvcrt.LK_UNLCK,1);operation.close()
    except Exception as error:
        if state.get('phase') not in ('rolled_back','migration_recovery_required'):
            state.update(phase='blocked',error=str(error) if isinstance(error,(RuntimeError,ValueError)) else type(error).__name__)
            if state.get('commit'):state['failed_commit']=state['commit']
            save()
        raise
    finally:
        config.clear();poll.seek(0);msvcrt.locking(poll.fileno(),msvcrt.LK_UNLCK,1);poll.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--check-only',action='store_true');args=p.parse_args()
    try:main(args.check_only)
    except Exception as error:raise SystemExit(str(error) if isinstance(error,(RuntimeError,ValueError)) else 'AUTO_DEPLOY_BLOCKED: '+type(error).__name__+'; protected status retained; inspect before retry.') from None
