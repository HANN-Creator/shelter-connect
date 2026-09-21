import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_supabase


class SupabaseLauncherTest(unittest.TestCase):
    def setUp(self):
        self.values = {
            "DB_URL": "jdbc:postgresql://aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=require",
            "DB_USERNAME": "postgres.abcdefghijklmnopqrst",
            "DB_PASSWORD": "local-fake-only",
            "SUPABASE_URL": "https://abcdefghijklmnopqrst.supabase.co",
        }

    def load(self, changes=None, suffix=""):
        values = dict(self.values, **(changes or {}))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env.supabase"
            path.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + suffix)
            return run_supabase.load_settings(path)

    def test_password_is_literal_and_never_executed(self):
        password = "  'quoted'$HOME$(echo should-not-run)`id`#value=more  "
        self.assertEqual(password, self.load({"DB_PASSWORD": password})["DB_PASSWORD"])

    def test_empty_password_and_duplicates_are_rejected_without_echoing_values(self):
        with self.assertRaises(run_supabase.ConfigurationError):
            self.load({"DB_PASSWORD": ""})
        with self.assertRaises(run_supabase.ConfigurationError) as error:
            self.load(suffix="\nDB_PASSWORD=secret-not-to-log")
        self.assertNotIn("secret-not-to-log", str(error.exception))

    def test_wrong_project_user_and_unencrypted_or_arbitrary_targets_are_rejected(self):
        changes = [
            {"DB_USERNAME": "postgres.differentprojectref00"},
            {"DB_URL": self.values["DB_URL"].replace(":5432", ":6543")},
            {"DB_URL": self.values["DB_URL"].replace("sslmode=require", "sslmode=disable")},
            {"DB_URL": self.values["DB_URL"] + "&password=secret-not-to-log"},
            {"DB_URL": "jdbc:postgresql://evil.example:5432/postgres?sslmode=require"},
            {"SUPABASE_URL": "http://abcdefghijklmnopqrst.supabase.co"},
            {"PORT": "80"}, {"DB_POOL_SIZE": "10"},
        ]
        for change in changes:
            with self.subTest(change=tuple(change.keys())), self.assertRaises(run_supabase.ConfigurationError) as error:
                self.load(change)
            self.assertNotIn("secret-not-to-log", str(error.exception))

    def test_inherited_settings_cannot_enable_migrations_ai_or_redirect_database(self):
        env, args = run_supabase.launch_settings(self.load(), {
            "PATH": "/bin", "SPRING_DATASOURCE_URL": "other", "SPRING_APPLICATION_JSON": "{}",
            "DB_MIGRATE": "true", "AI_ENABLED": "true", "OPENAI_API_KEY": "hidden",
            "JDK_JAVA_OPTIONS": "-Dspring.datasource.password=hidden",
        }, read_only=True)
        self.assertEqual("/bin", env["PATH"])
        self.assertEqual("false", env["DB_MIGRATE"])
        self.assertEqual("false", env["AI_ENABLED"])
        self.assertNotIn("SPRING_DATASOURCE_URL", env)
        self.assertNotIn("SPRING_APPLICATION_JSON", env)
        self.assertNotIn("JDK_JAVA_OPTIONS", env)
        self.assertNotIn("OPENAI_API_KEY", env)
        self.assertIn("--spring.datasource.hikari.read-only=true", args)
        self.assertIn("--spring.flyway.enabled=false", args)
        self.assertFalse(any(self.values["DB_PASSWORD"] in arg for arg in args))

    def test_config_only_never_starts_a_process_or_prints_password(self):
        capture = io.StringIO()
        with patch("sys.argv", ["run_supabase.py", "--check-config"]), patch.object(run_supabase, "load_settings", return_value=self.load()), \
                patch.object(run_supabase.os, "execve") as execute, contextlib.redirect_stdout(capture):
            self.assertEqual(0, run_supabase.main())
        execute.assert_not_called()
        self.assertNotIn(self.values["DB_PASSWORD"], capture.getvalue())


if __name__ == "__main__":
    unittest.main()
