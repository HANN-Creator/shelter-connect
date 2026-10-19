"""CI-only PostgreSQL proof: new versions commit together or roll back together."""
import os,subprocess,tempfile,uuid,zipfile
from pathlib import Path

def main():
    # Disposable CI credentials only. No production configuration is loaded.
    user=os.environ['TEST_DB_USERNAME'];password=os.environ['TEST_DB_PASSWORD']
    db='shelter_migration_check_'+uuid.uuid4().hex
    env=dict(os.environ,PGPASSWORD=password)
    def pg(sql,target='postgres'):
        p=subprocess.run(['psql','-X','-h','127.0.0.1','-U',user,'-d',target,'-At','-v','ON_ERROR_STOP=1','-c',sql],env=env,capture_output=True,check=True)
        return p.stdout.strip()
    pg('CREATE DATABASE '+db)
    try:
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);libs=root/'libs';libs.mkdir();sql=root/'sql';sql.mkdir()
            with zipfile.ZipFile('build/libs/shelter-connect-api.jar') as z:
                for name in z.namelist():
                    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):(libs/Path(name).name).write_bytes(z.read(name))
                    elif name.startswith('BOOT-INF/classes/db/migration/') and name.endswith('.sql'):(sql/Path(name).name).write_bytes(z.read(name))
            subprocess.run(['javac','-cp',str(libs/'*'),'-d',str(root),'scripts/mini_pc/Migrate.java'],check=True)
            env.update(MIGRATION_URL='jdbc:postgresql://127.0.0.1:5432/'+db,MIGRATION_USER=user,MIGRATION_PASSWORD=password,MIGRATION_SQL=str(sql))
            def migrate():return subprocess.run(['java','-Xmx192m','-cp',str(root)+os.pathsep+str(libs/'*'),'Migrate','migrate'],env=env,capture_output=True).returncode
            assert migrate()==0,'Baseline failed'
            (sql/'V25__transaction_probe.sql').write_text('CREATE TABLE shelter.migration_probe(id integer PRIMARY KEY);')
            (sql/'V26__transaction_failure.sql').write_text('SELECT 1/0;')
            assert migrate()!=0,'Expected failing migration'
            assert pg("SELECT to_regclass('shelter.migration_probe') IS NULL",db)==b't','DDL was not rolled back'
            assert pg("SELECT count(*) FROM shelter.flyway_schema_history WHERE version IN ('25','26')",db)==b'0','History was not rolled back'
            (sql/'V26__transaction_failure.sql').unlink()
            assert migrate()==0,'Valid forward migration failed'
            assert pg("SELECT count(*) FROM shelter.flyway_schema_history WHERE version='25' AND success",db)==b'1'
            assert migrate()==0,'Repeated deployment is not idempotent'
            print('MIGRATION_TRANSACTION_OK: PostgreSQL DDL/history rollback, successful forward application and idempotency verified.')
    finally:pg('DROP DATABASE '+db+' WITH (FORCE)')

if __name__=='__main__':main()
