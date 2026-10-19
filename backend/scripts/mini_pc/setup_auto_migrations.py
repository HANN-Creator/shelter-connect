"""One-time local-only setup. Run as administrator after reviewed CI passes."""
import ctypes,getpass,json,os,secrets,subprocess,sys,tempfile
from pathlib import Path
from backup_security import current_sid,read_json,protect_json

ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)==2 else None
if ROOT is None:raise SystemExit('Explicit workspace path required.')
HOME=Path(r'C:\ProgramData\ShelterConnectDeploy')
APP=Path(r'C:\ProgramData\ShelterConnectPreview')
SOURCE=ROOT/'work/auto-migrations/backend/scripts/mini_pc'
PG=Path(r'C:\Program Files\PostgreSQL\17\bin')

def command(args,env=None):
    p=subprocess.run([str(x) for x in args],env=env,capture_output=True,timeout=900)
    if p.returncode:raise RuntimeError('Local setup command failed; private command output withheld.')
    return p.stdout

def main():
    if not ctypes.windll.shell32.IsUserAnAdmin():raise RuntimeError('Run administrator PowerShell.')
    deploy=read_json(HOME/'config.dpapi')
    if deploy['owner_sid']!=current_sid():raise RuntimeError('Use the configured Windows deployment account.')
    review=json.loads((ROOT/'outputs/automatic-migration-review.json').read_text())
    commit=command(['git','-C',SOURCE,'rev-parse','HEAD']).decode().strip()
    if review.get('ci_passed') is not True or commit!=review['head']:raise RuntimeError('Reviewed CI/source commit mismatch.')
    if command(['git','-C',SOURCE,'status','--porcelain']).strip():raise RuntimeError('Reviewed migration source has uncommitted changes.')
    command(['powershell.exe','-NoProfile','-Command','Disable-ScheduledTask -TaskName ShelterGitHubDeploy | Out-Null'])
    try:
        state=command(['powershell.exe','-NoProfile','-Command','(Get-ScheduledTask -TaskName ShelterGitHubDeploy).State']).decode().strip()
        if state=='Running':raise RuntimeError('Deployment active; wait for completion before setup.')
        config_path=HOME/'migration.dpapi'
        if not config_path.exists():
            settings=dict(line.split('=',1) for line in (APP/'conf/runtime.env').read_text(encoding='utf-8-sig').splitlines() if '=' in line)
            db=settings['DB_URL'].removeprefix('jdbc:postgresql://127.0.0.1:5432/')
            import re
            if not re.fullmatch('shelter_prod_[a-z0-9_]+',db):raise RuntimeError('Unexpected production target.')
            password=getpass.getpass('LOCAL PostgreSQL postgres administrator password (hidden): ')
            env={k:v for k,v in os.environ.items() if not k.startswith('PG')}
            env.update(PGPASSWORD=password,PGCONNECT_TIMEOUT='15',PGCLIENTENCODING='UTF8')
            def pg(sql):return command([PG/'psql.exe','-X','-h','127.0.0.1','-U','postgres','-d',db,'-w','-At','-v','ON_ERROR_STOP=1','-c',sql],env)
            pg('SELECT 1')
            new_password=secrets.token_hex(32)
            # Administrative password is used for bootstrap only, never stored.
            sql="""BEGIN;
DO $$ BEGIN
 IF (SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='shelter') <> 'shelter_migration_owner'
 OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='shelter_migration_owner' AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolbypassrls AND NOT rolreplication)
 THEN RAISE EXCEPTION 'Unexpected existing schema owner'; END IF;
END $$;
CREATE ROLE shelter_migrator LOGIN NOSUPERUSER CREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '%s';
GRANT CONNECT ON DATABASE %s TO shelter_migrator;
GRANT shelter_migration_owner TO shelter_migrator WITH INHERIT FALSE, SET TRUE;
SET LOCAL ROLE shelter_migration_owner;
ALTER DEFAULT PRIVILEGES IN SCHEMA shelter GRANT SELECT ON TABLES TO shelter_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA shelter GRANT SELECT ON SEQUENCES TO shelter_backup;
COMMIT;""" % (new_password,db)
            # stdin instead of command arguments: no passwords in process command lines.
            p=subprocess.run([str(PG/'psql.exe'),'-X','-h','127.0.0.1','-U','postgres','-d',db,'-w','-v','ON_ERROR_STOP=1'],input=sql.encode(),env=env,capture_output=True,timeout=120)
            password=None;env.pop('PGPASSWORD',None)
            if p.returncode:raise RuntimeError('Migration role transaction failed; no partial ownership changes committed.')
            protect_json(config_path,{'username':'shelter_migrator','password':new_password,'database':db,'owner_sid':current_sid()})
            new_password=None
        # Rehearse current real data without applying a production schema migration.
        sys.path.insert(0,str(SOURCE))
        import auto_migrate
        test_dir=HOME/('setup-check-'+secrets.token_hex(6));test_dir.mkdir()
        migration=auto_migrate.Migration(APP/'app/shelter-connect-api.jar',test_dir)
        migration.rehearse()
        backup=HOME/('bin-before-migrations-'+secrets.token_hex(6));backup.mkdir()
        import shutil
        for name in ('pull_deploy.py','auto_migrate.py','Migrate.java','release_bundle.py','backup_security.py'):
            old=HOME/'bin'/name
            if old.exists():shutil.copy2(old,backup/name)
            shutil.copy2(SOURCE/name,old)
        print('AUTOMATIC_MIGRATIONS_READY: dedicated protected account, real-data restore/Flyway rehearsal passed; automatic forward migrations enabled. Production Flyway remains OFF during normal boot.')
    finally:
        command(['powershell.exe','-NoProfile','-Command','Enable-ScheduledTask -TaskName ShelterGitHubDeploy | Out-Null'])

if __name__=='__main__':
    try:main()
    except Exception as e:raise SystemExit(str(e) if isinstance(e,RuntimeError) else 'AUTOMATIC_MIGRATION_SETUP_FAILED: protected details withheld.') from None
