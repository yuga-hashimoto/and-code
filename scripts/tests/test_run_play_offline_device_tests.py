import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/run_play_offline_device_tests.sh'


class OfflineDeviceRunnerTest(unittest.TestCase):
    def run_with_result(self, report, serial='emulator-5554', network='none'):
        with tempfile.TemporaryDirectory() as directory:
            tmp = Path(directory)
            adb = tmp / 'adb'
            adb.write_text('''#!/bin/sh
case "$*" in
  "get-serialno") printf '%s\\n' "$FAKE_SERIAL" ;;
  *"getconf PAGE_SIZE"*) echo 16384 ;;
  *"dumpsys connectivity"*) echo "Active default network: $FAKE_NETWORK" ;;
  *"am instrument"*) printf '%s\\n' "$FAKE_REPORT" ;;
  *"logcat -d"*) echo 'synthetic logcat' ;;
esac
''')
            adb.chmod(0o755)
            sleep = tmp / 'sleep'
            sleep.write_text('#!/bin/sh\nexit 0\n')
            sleep.chmod(0o755)
            env = os.environ.copy()
            env.update(PATH=str(tmp) + os.pathsep + env['PATH'], RUNNER_TEMP=str(tmp),
                       FAKE_REPORT=report, FAKE_SERIAL=serial, FAKE_NETWORK=network)
            return subprocess.run(['bash', str(SCRIPT)], cwd=ROOT, env=env, text=True, capture_output=True)

    def test_succeeds_only_when_two_instrumented_tests_pass(self):
        self.assertEqual(0, self.run_with_result('OK (2 tests)').returncode)
        self.assertNotEqual(0, self.run_with_result('FAILURES!!!').returncode)
        self.assertNotEqual(0, self.run_with_result('OK (1 test)').returncode)

    def test_refuses_non_emulator_devices(self):
        self.assertNotEqual(0, self.run_with_result('OK (2 tests)', serial='physical-device').returncode)

    def test_fails_when_network_never_disconnects(self):
        result = self.run_with_result('OK (2 tests)', network='100')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('No stable offline state', result.stderr)


if __name__ == '__main__':
    unittest.main()
