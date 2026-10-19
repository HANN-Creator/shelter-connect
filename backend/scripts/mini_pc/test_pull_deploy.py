"""Exercise real directory swaps, failure recovery and preservation of DB/file data."""
import json,os,sys,tempfile,unittest,zipfile
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch
import pull_deploy as d

class DeploymentFlowTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.home=self.root/'deploy';self.home.mkdir()
        self.app_root=self.root/'runtime';self.app=self.app_root/'app';self.app.mkdir(parents=True)
        (self.app/'shelter-connect-api.jar').write_bytes(b'OLD APP')
        (self.app/'motion-requirements.txt').write_text('Pillow==12.3.0\n')
        self.data=self.app_root/'data';self.data.mkdir();(self.data/'photo.blob').write_bytes(b'PERMANENT PHOTO')
        self.backup=self.root/'backup';self.backup.mkdir();(self.backup/'operation.lock').write_bytes(b'0')
        self.head='a'*40;self.events=[]
        self.manifest={'commit':self.head,'release_run_id':17,'test_run_id':19,'migrations':{'V1':'unchanged'}}
        self.stack=ExitStack();self.addCleanup(self.stack.close)
        self.stack.enter_context(patch('builtins.print'))
        patches={'HOME':self.home,'APP_ROOT':self.app_root,'BACKUP_HOME':self.backup,
          'read_json':lambda p:{'owner_sid':'test','enabled':True,'workspace':str(self.root),'python':sys.executable},
          'current_sid':lambda:'test','api':self.api,'download':self.download,'unpack':self.unpack,
          'migrations':lambda p:{'V1':'unchanged'},'command':self.command,'service':self.service,'health':lambda u:None,'available_memory':lambda:2*1024**3,'installed_version':lambda name:'12.3.0'}
        for name,value in patches.items():self.stack.enter_context(patch.object(d,name,value))
        if os.name!='nt':
            from types import SimpleNamespace
            self.stack.enter_context(patch.dict(sys.modules,{'msvcrt':SimpleNamespace(LK_NBLCK=1,LK_UNLCK=2,locking=lambda *a:None)}))
    def api(self,config,path):
        if path=='/commits/main':return {'sha':self.head}
        if path.startswith('/actions/workflows/dependency-security.yml'):
            return {'workflow_runs':[{'id':21,'head_sha':self.head,'head_branch':'main','event':'push','conclusion':'success','path':'.github/workflows/dependency-security.yml','repository':{'full_name':d.REPOSITORY}}]}
        if path.startswith('/actions/workflows/'):
            return {'workflow_runs':[{'id':17,'event':'workflow_run','path':'.github/workflows/mini-pc-release.yml','repository':{'full_name':d.REPOSITORY}}]}
        if path=='/actions/runs/17/artifacts':return {'artifacts':[{'name':'mini-pc-release-'+self.head,'id':23,'expired':False}]}
        if path=='/actions/runs/19':return {'conclusion':'success','event':'push','head_branch':'main','head_sha':self.head,'path':'.github/workflows/backend.yml','repository':{'full_name':d.REPOSITORY}}
        raise AssertionError('Unexpected API request: '+path)
    def download(self,config,artifact,path):
        with zipfile.ZipFile(path,'w') as z:z.writestr('release.zip',b'fixture')
    def unpack(self,path,target):
        app=target/'app';app.mkdir(parents=True)
        (app/'shelter-connect-api.jar').write_bytes(b'NEW APP')
        (app/'motion-requirements.txt').write_text('Pillow==12.3.0\n')
        return self.manifest
    def command(self,args,timeout=300):
        if len(args)>1 and str(args[1]).endswith('production_backup.py'):self.events.append('BACKUP')
    def service(self,action,name):self.events.append(action+':'+name)
    def state(self):return json.loads((self.home/'last-status.json').read_text())
    def test_success_backs_up_before_switch_and_preserves_photo(self):
        d.main();self.assertEqual(self.events[0],'BACKUP')
        self.assertEqual((self.app/'shelter-connect-api.jar').read_bytes(),b'NEW APP')
        self.assertEqual((self.data/'photo.blob').read_bytes(),b'PERMANENT PHOTO')
        self.assertEqual(self.state()['deployed_commit'],self.head)
        self.assertNotIn('failed_commit',self.state())
    def test_failed_readiness_rolls_back_code_without_replacing_data(self):
        with patch.object(d,'health',side_effect=[RuntimeError('candidate failed'),None]),self.assertRaises(RuntimeError):d.main()
        self.assertEqual((self.app/'shelter-connect-api.jar').read_bytes(),b'OLD APP')
        self.assertEqual((self.data/'photo.blob').read_bytes(),b'PERMANENT PHOTO')
        self.assertEqual(self.state()['phase'],'rolled_back')
        self.events.clear();d.main();self.assertEqual(self.events,[],'Failed commit must not restart deployment on every poll.')
    def test_changed_migration_blocks_before_backup_or_service_stop(self):
        self.manifest['migrations']={'V1':'changed'}
        with self.assertRaises(RuntimeError):d.main()
        self.assertEqual(self.events,[]);self.assertEqual(self.state()['phase'],'blocked')
    def test_backup_failure_leaves_existing_services_and_code(self):
        with patch.object(d,'command',side_effect=RuntimeError('backup failed')),self.assertRaises(RuntimeError):d.main()
        self.assertEqual(self.events,[]);self.assertEqual((self.app/'shelter-connect-api.jar').read_bytes(),b'OLD APP')
    def test_check_only_cannot_stop_services_or_change_app(self):
        d.main(check_only=True);self.assertEqual(self.events,[])
        self.assertEqual((self.app/'shelter-connect-api.jar').read_bytes(),b'OLD APP')
    def test_security_failure_waits_without_downloading_or_stopping(self):
        original=self.api
        def api(config,path):
            if path.startswith('/actions/workflows/dependency-security.yml'):return {'workflow_runs':[]}
            return original(config,path)
        with patch.object(d,'api',api),patch.object(d,'download') as download:
            d.main();download.assert_not_called()
        self.assertEqual(self.events,[]);self.assertEqual(self.state()['phase'],'waiting_for_security_check')
    def test_commit_exhaustion_waits_without_touching_services(self):
        with patch.object(d,'available_memory',return_value=128*1024**2),patch.object(d,'api') as api:
            d.main();api.assert_not_called()
        self.assertEqual(self.events,[]);self.assertEqual(self.state()['phase'],'waiting_for_memory')

if __name__=='__main__':unittest.main()
