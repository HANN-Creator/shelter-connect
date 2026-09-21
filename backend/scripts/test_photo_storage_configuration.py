import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import check_photo_storage
import run_supabase


class PhotoStorageConfigurationTest(unittest.TestCase):
    def load(self, content):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env.storage"
            path.write_text(content)
            return run_supabase.load_storage_settings(path)

    def test_only_server_key_and_restricted_settings_are_accepted(self):
        values = self.load("SUPABASE_SECRET_KEY=sb_secret_test_only")
        self.assertEqual("dog-photos", values["PHOTO_STORAGE_BUCKET"])
        for content in (
            "SUPABASE_SECRET_KEY=sb_publishable_test_only",
            "SUPABASE_SECRET_KEY='sb_secret_test_only'",
            "SUPABASE_SECRET_KEY=sb_secret_test_only\nSUPABASE_URL=https://elsewhere.invalid",
            "SUPABASE_SECRET_KEY=sb_secret_test_only\nPHOTO_STORAGE_BUCKET=../other",
            "SUPABASE_SECRET_KEY=sb_secret_test_only\nPHOTO_STORAGE_TIMEOUT_SECONDS=11",
            "SUPABASE_SECRET_KEY=sb_secret_test_only\nSUPABASE_SECRET_KEY=duplicate",
        ):
            with self.subTest(content=content), self.assertRaises(run_supabase.ConfigurationError) as error:
                self.load(content)
            self.assertNotIn("sb_secret_test_only", str(error.exception))

    def test_storage_requires_explicit_opt_in_and_keys_never_become_arguments(self):
        values = {"DB_PASSWORD": "fake-db-password", "PORT": "8080"}
        inherited = {"PHOTO_STORAGE_ENABLED": "true", "SUPABASE_SECRET_KEY": "sb_secret_inherited"}
        env, flags = run_supabase.launch_settings(values, inherited)
        self.assertEqual("false", env["PHOTO_STORAGE_ENABLED"])
        self.assertNotIn("SUPABASE_SECRET_KEY", env)
        storage = self.load("SUPABASE_SECRET_KEY=sb_secret_test_only")
        env, flags = run_supabase.launch_settings(values, inherited, read_only=True, storage=storage)
        self.assertEqual("true", env["PHOTO_STORAGE_ENABLED"])
        self.assertEqual("sb_secret_test_only", env["SUPABASE_SECRET_KEY"])
        self.assertIn("--app.photos.enabled=true", flags)
        self.assertIn("--spring.datasource.hikari.read-only=true", flags)
        self.assertIn("--app.ai.enabled=false", flags)
        self.assertNotIn("sb_secret_test_only", str(flags))

    def test_config_check_prints_neither_key_nor_password_and_never_executes(self):
        capture = io.StringIO()
        with patch("sys.argv", ["run_supabase.py", "--with-photos", "--check-config"]), \
                patch.object(run_supabase, "load_settings", return_value={"DB_PASSWORD": "fake-db-password"}), \
                patch.object(run_supabase, "load_storage_settings", return_value={"SUPABASE_SECRET_KEY": "sb_secret_test_only"}), \
                patch.object(run_supabase.os, "execve") as execute, contextlib.redirect_stdout(capture):
            self.assertEqual(0, run_supabase.main())
        execute.assert_not_called()
        self.assertNotIn("fake-db-password", capture.getvalue())
        self.assertNotIn("sb_secret_test_only", capture.getvalue())

    def test_live_check_never_receives_database_password_and_does_not_prepare_by_default(self):
        values = {"DB_PASSWORD": "fake-db-password", "SUPABASE_URL": "https://abcdefghijklmnopqrst.supabase.co"}
        storage = self.load("SUPABASE_SECRET_KEY=sb_secret_test_only")
        for prepare in (False, True):
            with patch("sys.argv", ["check_photo_storage.py"] + (["--prepare"] if prepare else [])), \
                    patch.object(check_photo_storage, "load_settings", return_value=values), \
                    patch.object(check_photo_storage, "load_storage_settings", return_value=storage), \
                    patch.object(check_photo_storage.os, "chdir"), patch.object(check_photo_storage.os, "execve") as execute:
                check_photo_storage.main()
            _, arguments, environment = execute.call_args.args
            self.assertNotIn("DB_PASSWORD", environment)
            self.assertEqual(prepare, "--args=--prepare" in arguments)
            self.assertNotIn("sb_secret_test_only", str(arguments))


if __name__ == "__main__":
    unittest.main()
