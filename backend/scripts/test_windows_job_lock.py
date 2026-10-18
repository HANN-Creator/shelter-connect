"""Paid-job exclusion must work across processes, including Windows."""
import os,subprocess,sys,tempfile,unittest
from pathlib import Path
from styled_dog.client import read,write

class CrossProcessJournalTests(unittest.TestCase):
    def test_utf8_journal_roundtrip(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'journal.json';value={'name':'보호소 강아지'}
            write(path,value);self.assertEqual(read(path),value);self.assertIn('보호소',path.read_text(encoding='utf-8'))
    def test_second_process_waits_until_first_releases(self):
        with tempfile.TemporaryDirectory() as folder:
            code="from styled_dog.client import job_lock; import sys; from pathlib import Path\nwith job_lock(sys.argv[1]):\n print('ACQUIRED',flush=True)\n sys.stdin.readline()\n"
            args=[sys.executable,'-c',code,str(Path(folder)/'job.lock')]
            env=dict(os.environ,PYTHONPATH=str(Path(__file__).resolve().parent))
            first=subprocess.Popen(args,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
            second=None
            try:
                self.assertEqual(first.stdout.readline().strip(),'ACQUIRED')
                second=subprocess.Popen(args,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                with self.assertRaises(subprocess.TimeoutExpired):second.communicate(input='\n',timeout=0.3)
                first.communicate(input='\n',timeout=10)
                output,error=second.communicate(timeout=10)
                self.assertEqual(second.returncode,0,error);self.assertEqual(output.strip(),'ACQUIRED')
            finally:
                for process in (first,second):
                    if process is not None:
                        if process.poll() is None:process.kill()
                        process.communicate()

if __name__=='__main__':unittest.main()
