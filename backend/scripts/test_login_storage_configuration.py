import unittest
from check_login_storage import check_settings
from run_supabase import ConfigurationError


class LoginStorageConfigurationTest(unittest.TestCase):
    def test_project_must_be_explicit_and_match_before_launch(self):
        values = {'SUPABASE_URL': 'https://' + 'a' * 20 + '.supabase.co'}
        check_settings(values, 'a' * 20)
        for target in (None, '', 'b' * 20, 'a' * 20 + '.other'):
            with self.assertRaises(ConfigurationError):
                check_settings(values, target)

    def test_error_does_not_echo_untrusted_project_value(self):
        with self.assertRaises(ConfigurationError) as caught:
            check_settings({'SUPABASE_URL': 'private-value'}, 'private-target')
        self.assertNotIn('private', str(caught.exception))


if __name__ == '__main__':
    unittest.main()
