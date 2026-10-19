"""Deployment-only Flyway, restored-DB rehearsal and transaction-only forward migrations."""
import os,re,subprocess,uuid,zipfile,time,urllib.request,json
from pathlib import Path
from backup_security import read_json

PG=Path(r'C:\Program Files\PostgreSQL\17\bin')
RUNTIME=Path(r'C:\ProgramData\ShelterConnectPreview')
CONFIG=Path(r'C:\ProgramData\ShelterConnectDeploy\migration.dpapi')

def plan(old,new,jar):
    if any(new.get(name)!=digest for name,digest in old.items()):
        raise RuntimeError('Applied migration changed or disappeared; append a new version instead.')
    pending=sorted(set(new)-set(old))
    if not pending:return []
    def number(name):
        match=re.fullmatch(r'BOOT-INF/classes/db/migration/V([1-9][0-9]*)__[A-Za-z0-9_]+\.sql',name)
        if not match:raise RuntimeError('Automatic deployment accepts integer versioned SQL only.')
        return int(match[1])
    latest=max(number(n) for n in old)
    versions=[number(n) for n in new]
    if len(versions)!=len(set(versions)) or any(number(n)<=latest for n in pending):
        raise RuntimeError('Migration versions must be unique and strictly newer than the deployed release.')
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith('BOOT-INF/classes/db/migration/') and name not in new and not name.endswith('/'):
                raise RuntimeError('Migration callbacks/configuration/non-SQL files require manual review.')
        for name in pending:
            sql=z.read(name).decode('utf-8-sig')
            # Conservative guard, not a SQL security sandbox. Reviewed repository SQL is trusted.
            if re.search(r'\b(CONCURRENTLY|VACUUM|COMMIT|ROLLBACK|START\s+TRANSACTION|CREATE\s+DATABASE|DROP\s+DATABASE|ALTER\s+SYSTEM|COPY\b[\s\S]*?PROGRAM)\b',
                         re.sub(r'--[^\n]*','',sql),re.I):
                raise RuntimeError('Migration contains transaction/control operations requiring manual review.')
    if not CONFIG.exists():raise RuntimeError('One-time protected migration account setup required.')
    return pending

def run(args,env=None,timeout=600):
    p=subprocess.run([str(a) for a in args],env=env,capture_output=True,timeout=timeout)
    if p.returncode:raise RuntimeError('Migration database command failed; credentials/SQL output withheld.')
    return p.stdout

class Migration:
    def __init__(self,jar,release):
        self.secret=read_json(CONFIG)
        settings=dict(line.split('=',1) for line in (RUNTIME/'conf/runtime.env').read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
        self.runtime=settings
        self.jar=jar
        self.db=self.secret['database']
        if not re.fullmatch(r'shelter_prod_[a-z0-9_]+',self.db) or settings['DB_URL']!='jdbc:postgresql://127.0.0.1:5432/'+self.db:
            raise RuntimeError('Migration production target mismatch.')
        self.env={k:v for k,v in os.environ.items() if not k.startswith(('PG','MIGRATION_','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS'))}
        self.env.update(PGPASSWORD=self.secret['password'],PGCONNECT_TIMEOUT='15',PGCLIENTENCODING='UTF8')
        self.work=release/'migration'; self.work.mkdir()
        libs=self.work/'libs';libs.mkdir();self.sql=self.work/'sql';self.sql.mkdir()
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (libs/Path(name).name).write_bytes(z.read(name))
                elif re.fullmatch(r'BOOT-INF/classes/db/migration/V[1-9][0-9]*__[A-Za-z0-9_]+\.sql',name):
                    (self.sql/Path(name).name).write_bytes(z.read(name))
        self.java=Path((RUNTIME/'conf/java.path').read_text().strip())
        self.cp=str(self.work)+os.pathsep+str(libs/'*')
        run([self.java.with_name('javac.exe'),'-J-Xmx128m','-encoding','UTF-8','-cp',str(libs/'*'),'-d',self.work,Path(__file__).with_name('Migrate.java')],timeout=120)

    def pg(self,sql,database='postgres'):
        return run([PG/'psql.exe','-X','-h','127.0.0.1','-U',self.secret['username'],'-d',database,'-w','-At','-v','ON_ERROR_STOP=1','-c',sql],self.env)

    def apply(self,database):
        env=dict(self.env,MIGRATION_URL='jdbc:postgresql://127.0.0.1:5432/'+database,
                 MIGRATION_USER=self.secret['username'],MIGRATION_PASSWORD=self.secret['password'],MIGRATION_SQL=str(self.sql))
        run([self.java,'-Xms32m','-Xmx192m','-XX:ActiveProcessorCount=2','-cp',self.cp,'Migrate','migrate'],env)

    def rehearse(self):
        shadow='shelter_migration_check_'+uuid.uuid4().hex
        dump=self.work/'rehearsal.dump'
        run([PG/'pg_dump.exe','-h','127.0.0.1','-U',self.secret['username'],'-d',self.db,'-w','-Fc','--schema=shelter','--no-owner','-f',dump],self.env)
        self.pg('CREATE DATABASE '+shadow+' TEMPLATE template0')
        self.pg('REVOKE CONNECT ON DATABASE '+shadow+' FROM PUBLIC')
        try:
            run([PG/'pg_restore.exe','-h','127.0.0.1','-U',self.secret['username'],'-d',shadow,'-w','--no-owner','--single-transaction','--exit-on-error',dump],self.env)
            self.apply(shadow)
            self.check_app(shadow)
            bad=self.pg("SELECT count(*) FROM pg_constraint WHERE connamespace='shelter'::regnamespace AND contype='f' AND NOT convalidated",shadow).strip()
            if bad!=b'0':raise RuntimeError('Restored migration has unvalidated foreign keys.')
            unsafe=self.pg("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='shelter' AND c.relkind IN ('r','p') AND (NOT c.relrowsecurity OR NOT has_table_privilege('shelter_backup',c.oid,'SELECT'))",shadow).strip()
            if unsafe!=b'0':raise RuntimeError('Migration must retain RLS and complete backup SELECT privileges.')
            if self.pg("SELECT has_schema_privilege('shelter_runtime','shelter','CREATE')",shadow).strip()!=b'f':
                raise RuntimeError('Migration must not grant runtime schema creation.')
        finally:
            # Only the random DB created by this method; never production/auth.
            self.pg('DROP DATABASE '+shadow+' WITH (FORCE)')

    def check_app(self,database):
        self.pg('GRANT CONNECT ON DATABASE '+database+' TO shelter_runtime')
        env={k:v for k,v in self.env.items() if not k.startswith(('PG','SPRING_','DB_','AI_','OPENAI_','ASSET_','PIXELLAB_','SUPABASE_','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS'))}
        env.update(DB_URL='jdbc:postgresql://127.0.0.1:5432/'+database,DB_USERNAME='shelter_runtime',DB_PASSWORD=self.runtime['DB_PASSWORD'],SUPABASE_URL=self.runtime['SUPABASE_URL'],DB_POOL_SIZE='2')
        args=[str(self.java),'-Xms32m','-Xmx192m','-XX:ActiveProcessorCount=2','-jar',str(self.jar),
              '--spring.config.location=classpath:/application.properties','--spring.flyway.enabled=false',
              '--spring.sql.init.mode=never','--spring.jpa.hibernate.ddl-auto=validate',
              '--server.address=127.0.0.1','--server.port=18083','--app.ai.enabled=false',
              '--app.assets.enabled=false','--app.assets.worker-enabled=false','--app.assets.auto-import=false',
              '--app.photos.enabled=false','--app.community.media-enabled=false']
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            opener.open('http://127.0.0.1:18083/actuator/health/readiness',timeout=1).close()
            raise RuntimeError('Migration rehearsal port is already occupied.')
        except OSError:pass
        process=subprocess.Popen(args,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        try:
            for _ in range(60):
                if process.poll() is not None:raise RuntimeError('Candidate application failed against restored migration DB.')
                try:
                    with opener.open('http://127.0.0.1:18083/actuator/health/readiness',timeout=2) as response:
                        if response.status==200 and json.load(response).get('status')=='UP':return
                except OSError:pass
                time.sleep(2)
            raise RuntimeError('Candidate migration rehearsal readiness failed.')
        finally:
            process.terminate()
            try:process.wait(timeout=30)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)

    def production(self):
        # Backend/tunnel are stopped by caller. Preserve the exact final app data
        # immediately before the transaction, including writes since encrypted backup.
        run([PG/'pg_dump.exe','-h','127.0.0.1','-U',self.secret['username'],'-d',self.db,'-w','-Fc','--schema=shelter','--no-owner','-f',self.work/'frozen-before-migration.dump'],self.env)
        self.apply(self.db)
