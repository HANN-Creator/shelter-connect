"""Exercise the deployment entry point offline; never contact a repository."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('build_with_retry.sh')
RATE_LIMIT = "> Could not GET 'https://repo.maven.apache.org/example.pom'. Received status code 429 from server: Too Many Requests"


class BuildRetryTest(unittest.TestCase):
    def run_build(self, outcomes):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for index, (code, log) in enumerate(outcomes, 1):
                (root / f'{index}.code').write_text(str(code))
                (root / f'{index}.log').write_text(log)
            (root / 'gradlew').write_text('''#!/bin/sh
count=0
if [ -f count ]; then count=$(cat count); fi
count=$((count + 1))
echo "$count" > count
printf '%s\\n' "$*" >> args
cat "$count.log"
exit "$(cat "$count.code")"
''')
            (root / 'gradlew').chmod(0o700)
            (root / 'sleep').write_text('#!/bin/sh\nprintf "%s\\n" "$1" >> delays\n')
            (root / 'sleep').chmod(0o700)
            result = subprocess.run(['bash', str(SCRIPT), 'resolveDeploymentDependencies'],
                cwd=root, env={**os.environ, 'PATH': str(root) + os.pathsep + os.environ['PATH']},
                capture_output=True, text=True, timeout=10)
            return (result, int((root / 'count').read_text()),
                    (root / 'delays').read_text().splitlines() if (root / 'delays').exists() else [],
                    (root / 'args').read_text().splitlines())

    def test_rate_limit_recovers_without_losing_arguments_or_log(self):
        result, count, delays, args = self.run_build([(1, RATE_LIMIT), (0, 'BUILD SUCCESSFUL')])
        self.assertEqual((result.returncode, count, delays), (0, 2, ['60']))
        self.assertIn(RATE_LIMIT, result.stdout)
        self.assertEqual(args, ['--no-daemon --max-workers=2 --console=plain resolveDeploymentDependencies'] * 2)

    def test_persistent_rate_limit_is_bounded_and_stays_failed(self):
        result, count, delays, _ = self.run_build([(7, RATE_LIMIT)] * 3)
        self.assertEqual((result.returncode, count, delays), (7, 3, ['60', '180']))

    def test_success_runs_once(self):
        result, count, delays, _ = self.run_build([(0, 'BUILD SUCCESSFUL')])
        self.assertEqual((result.returncode, count, delays), (0, 1, []))

    def test_other_failures_are_not_retried(self):
        for log in ('Compilation failed: error: missing symbol',
                    "Could not GET 'https://repo.maven.apache.org/missing.pom'. Received status code 404",
                    'Gradle build daemon disappeared unexpectedly',
                    RATE_LIMIT + '\nCompilation failed: error: missing symbol'):
            with self.subTest(log=log):
                result, count, delays, _ = self.run_build([(2, log)])
                self.assertEqual((result.returncode, count, delays), (2, 1, []))


if __name__ == '__main__':
    unittest.main()
