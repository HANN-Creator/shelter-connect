from pathlib import Path
import unittest
from unittest.mock import patch
from check_login_storage import runtime_environment
from run_supabase import ConfigurationError


class RuntimeConfigurationTest(unittest.TestCase):
    def test_fixture_and_application_credentials_are_separated(self):
        ref = 'a' * 20
        values = {'SUPABASE_URL': f'https://{ref}.supabase.co',
                  'DB_USERNAME': f'shelter_runtime.{ref}', 'DB_PASSWORD': 'fake-runtime',
                  'DB_URL': 'validated-url'}
        with patch('check_login_storage.load_settings', return_value=values):
            result = runtime_environment(Path('/tmp/test-only'), ref)
            self.assertEqual('fake-runtime', result['RUNTIME_DB_PASSWORD'])
            self.assertNotIn('DB_PASSWORD', result)
            values['DB_USERNAME'] = f'postgres.{ref}'
            with self.assertRaises(ConfigurationError):
                runtime_environment(Path('/tmp/test-only'), ref)
