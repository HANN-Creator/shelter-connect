import tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import auto_migrate as m
from test_pull_deploy import DeploymentFlowTests
import pull_deploy as d

PREFIX='BOOT-INF/classes/db/migration/'
class PlanTests(unittest.TestCase):
    def setUp(self):
        self.t=tempfile.TemporaryDirectory();self.addCleanup(self.t.cleanup)
        self.jar=Path(self.t.name)/'app.jar'
        self.old={PREFIX+'V24__old.sql':'old'}
        self.new={**self.old,PREFIX+'V25__new.sql':'new'}
    def jar_sql(self,sql,extra=None):
        with zipfile.ZipFile(self.jar,'w') as z:
            z.writestr(PREFIX+'V25__new.sql',sql)
            for name,text in (extra or {}).items():z.writestr(PREFIX+name,text)
    def test_append_only_with_config(self):
        self.jar_sql('ALTER TABLE shelter.dogs ADD COLUMN example text;')
        with patch.object(m,'CONFIG',self.jar):self.assertEqual(m.plan(self.old,self.new,self.jar),[PREFIX+'V25__new.sql'])
    def test_changed_or_removed_history_rejected(self):
        for new in ({}, {PREFIX+'V24__old.sql':'changed'}):
            with self.assertRaises(RuntimeError):m.plan(self.old,new,self.jar)
    def test_no_config_blocks_new_migrations(self):
        self.jar_sql('SELECT 1;')
        with patch.object(m,'CONFIG',Path(self.t.name)/'absent'),self.assertRaises(RuntimeError):m.plan(self.old,self.new,self.jar)
    def test_nontransactional_and_commit_refused(self):
        for sql in ('CREATE INDEX CONCURRENTLY example ON shelter.dogs(id);','COMMIT;','VACUUM shelter.dogs;'):
            self.jar_sql(sql)
            with self.assertRaises(RuntimeError):m.plan(self.old,self.new,self.jar)
    def test_callback_config_refused(self):
        self.jar_sql('SELECT 1;',{'V25__new.sql.conf':'executeInTransaction=false'})
        with self.assertRaises(RuntimeError):m.plan(self.old,self.new,self.jar)
    def test_do_block_allowed(self):
        self.jar_sql('DO $$ BEGIN PERFORM 1; END $$;')
        with patch.object(m,'CONFIG',self.jar):self.assertTrue(m.plan(self.old,self.new,self.jar))

class MigrationFlowTests(DeploymentFlowTests):
    def setUp(self):
        super().setUp()
        self.stack.enter_context(patch.object(d,'migration_plan',return_value=['V25']))
        self.migration=self.stack.enter_context(patch.object(d,'Migration')).return_value
        self.migration.rehearse.side_effect=lambda:self.events.append('REHEARSE')
        self.migration.production.side_effect=lambda:self.events.append('MIGRATE')
    # Inherited cases have a no-schema-change contract; test dedicated changed-schema paths here.
    def test_success_backs_up_before_switch_and_preserves_photo(self):
        d.main()
        self.assertLess(self.events.index('BACKUP'),self.events.index('REHEARSE'))
        self.assertLess(self.events.index('REHEARSE'),self.events.index('Stop:ShelterConnectTunnel'))
        self.assertLess(self.events.index('Stop:ShelterConnectPreviewBackend'),self.events.index('MIGRATE'))
        self.assertLess(self.events.index('MIGRATE'),self.events.index('Start:ShelterConnectPreviewBackend'))
        self.assertEqual(self.state()['phase'],'deployed')
    def test_failed_readiness_rolls_back_code_without_replacing_data(self):
        with patch.object(d,'health',side_effect=RuntimeError('bad candidate')),self.assertRaises(RuntimeError):d.main()
        self.assertEqual(self.state()['phase'],'migration_recovery_required')
        self.assertEqual((self.app/'shelter-connect-api.jar').read_bytes(),b'NEW APP')
        self.assertEqual((self.data/'photo.blob').read_bytes(),b'PERMANENT PHOTO')
        self.events.clear();d.main();self.assertEqual(self.events,[])
    def test_changed_migration_blocks_before_backup_or_service_stop(self):
        with patch.object(d,'migration_plan',side_effect=RuntimeError('changed')),self.assertRaises(RuntimeError):d.main()
        self.assertEqual(self.events,[])
    def test_rehearsal_failure_does_not_stop_services(self):
        self.migration.rehearse.side_effect=RuntimeError('bad SQL')
        with self.assertRaises(RuntimeError):d.main()
        self.assertEqual(self.events,['BACKUP'])
    def test_production_migration_failure_keeps_offline_for_recovery(self):
        self.migration.production.side_effect=RuntimeError('unknown commit outcome')
        with self.assertRaises(RuntimeError):d.main()
        self.assertEqual(self.state()['phase'],'migration_recovery_required')
        self.assertNotIn('Start:ShelterConnectPreviewBackend',self.events)

if __name__=='__main__':unittest.main()
