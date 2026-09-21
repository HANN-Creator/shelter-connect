import argparse
import unittest
from check_login_storage import check_settings, deployment_origin
from run_supabase import ConfigurationError


class LoginStorageConfigurationTest(unittest.TestCase):
    def test_deployment_origin_rejects_redirect_hosts_and_embedded_secrets(self):
        self.assertEqual(deployment_origin('https://shelter-connect-dev.onrender.com'),
                         'https://shelter-connect-dev.onrender.com')
        for target in ('http://server.onrender.com', 'https://server.onrender.com.evil.test',
                       'https://secret@server.onrender.com', 'https://server.onrender.com?key=secret',
                       'https://server.onrender.com/', 'https://server.onrender.com:443'):
            with self.assertRaises(argparse.ArgumentTypeError) as caught:
                deployment_origin(target)
            self.assertNotIn('secret', str(caught.exception))

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
