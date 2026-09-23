import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import check_ai_live
from run_supabase import load_ai_settings, launch_settings, ConfigurationError


class AiConfigurationTest(unittest.TestCase):
    def load(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / '.env.ai'
            path.write_text(text)
            return load_ai_settings(path)

    def test_opt_in_is_explicit_and_secret_never_becomes_a_command_argument(self):
        ai = self.load('OPENAI_API_KEY=sk-fake-unit-test')
        self.assertEqual('gpt-5.6-luna', ai['OPENAI_MODEL'])
        disabled, flags = launch_settings({}, ai | {'AI_ENABLED': 'true'})
        self.assertEqual('false', disabled['AI_ENABLED'])
        self.assertNotIn('OPENAI_API_KEY', disabled)
        enabled, flags = launch_settings({}, {'OPENAI_API_KEY': 'unrelated'}, ai=ai)
        self.assertEqual('true', enabled['AI_ENABLED'])
        self.assertEqual(ai['OPENAI_API_KEY'], enabled['OPENAI_API_KEY'])
        self.assertIn('--app.ai.enabled=true', flags)
        self.assertNotIn(ai['OPENAI_API_KEY'], ' '.join(flags))

    def test_invalid_key_model_timeout_and_extra_config_are_rejected_without_values(self):
        samples = ['', 'OPENAI_API_KEY=private-value', 'OPENAI_API_KEY=sk-fake\nOPENAI_MODEL=private-value/inject',
                   'OPENAI_API_KEY=sk-fake\nAI_TIMEOUT_SECONDS=61', 'OPENAI_API_KEY=sk-fake\nAI_TIMEOUT_SECONDS=4',
                   'OPENAI_API_KEY=sk-fake\nAI_TIMEOUT_SECONDS=３０',
                   'OPENAI_API_KEY=sk-fake\nOPENAI_API_KEY=private-value',
                   'OPENAI_API_KEY=sk-fake\nSPRING_APPLICATION_JSON=private-value']
        for sample in samples:
            with self.subTest(sample=sample), self.assertRaises(ConfigurationError) as error:
                self.load(sample)
            self.assertNotIn('private-value', str(error.exception))

    def test_documented_behavior_limit_is_accepted_and_bounded(self):
        self.assertEqual('20', self.load('OPENAI_API_KEY=sk-fake')['BEHAVIOR_AI_DAILY_LIMIT'])
        ai = self.load('OPENAI_API_KEY=sk-fake\nBEHAVIOR_AI_DAILY_LIMIT=7')
        env, _ = launch_settings({}, {'BEHAVIOR_AI_DAILY_LIMIT': '99'}, ai=ai)
        self.assertEqual('7', env['BEHAVIOR_AI_DAILY_LIMIT'])
        for value in ['0', '101', '-1', 'abc']:
            with self.assertRaises(ConfigurationError):
                self.load('OPENAI_API_KEY=sk-fake\nBEHAVIOR_AI_DAILY_LIMIT=' + value)

    def test_config_check_makes_no_call_or_process_and_does_not_print_secrets(self):
        capture = io.StringIO()
        with patch('sys.argv', ['check_ai_live.py', '--project-ref', 'a' * 20, '--check-config']), \
             patch.object(check_ai_live, 'load_settings', return_value={'SUPABASE_URL': 'https://' + 'a' * 20 + '.supabase.co'}), \
             patch.object(check_ai_live, 'load_ai_settings', return_value={'OPENAI_MODEL': 'gpt-5.6-luna', 'OPENAI_API_KEY': 'private-ai'}), \
             patch.object(check_ai_live, 'load_storage_settings', return_value={'SUPABASE_SECRET_KEY': 'private-auth'}), \
             patch.object(check_ai_live.os, 'execve') as execute, contextlib.redirect_stdout(capture):
            self.assertEqual(0, check_ai_live.main())
        execute.assert_not_called()
        self.assertNotIn('private-', capture.getvalue())


if __name__ == '__main__':
    unittest.main()
