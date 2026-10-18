import io,json,tempfile,unittest,zipfile
from pathlib import Path
from release_bundle import *

class ReleaseSafetyTests(unittest.TestCase):
    def test_windows_zip_slip_and_special_files(self):
        for name in ('../escape','/absolute','C:/file','app/../file','app\\file','app/CON.txt','app/file.','app//file'):
            with self.subTest(name=name),self.assertRaises(ValueError):safe_name(name)
    def test_test_run_must_be_exact_successful_main_push(self):
        m={'commit':'a'*40};run={'conclusion':'success','event':'push','head_branch':'main','head_sha':'a'*40,'path':'.github/workflows/backend.yml','repository':{'full_name':REPOSITORY}}
        require_tested_run(run,m)
        for key,value in [('event','pull_request'),('head_branch','feature'),('head_sha','b'*40),('conclusion','failure'),('path','.github/workflows/other.yml')]:
            with self.subTest(key=key),self.assertRaises(ValueError):require_tested_run(dict(run,**{key:value}),m)
    def test_old_supabase_only_jar_cannot_be_deployed(self):
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as z:z.writestr('BOOT-INF/classes/db/migration/V1__old.sql','select 1;')
        data.seek(0)
        with self.assertRaises(ValueError):migrations(data)
    def test_checksum_failure_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'release.zip';target=Path(d)/'target'
            m={'repository':REPOSITORY,'capability':CAPABILITY,'commit':'a'*40,'files':{'app/file':{'bytes':1,'sha256':sha(b'x')}}}
            with zipfile.ZipFile(path,'w') as z:z.writestr('release-manifest.json',json.dumps(m));z.writestr('app/file',b'y')
            with self.assertRaises(ValueError):unpack(path,target)
            self.assertFalse(target.exists())

if __name__=='__main__':unittest.main()
