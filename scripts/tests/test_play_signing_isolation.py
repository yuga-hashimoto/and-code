"""Integration checks for distribution-specific release signing configuration."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PASSWORD = "test-password"


def signing_config(output, variant):
    match = re.search(rf"(?m)^Variant: {variant}\nConfig: (.+)$", output)
    if not match:
        raise AssertionError(f"Missing signing report for {variant}")
    return match.group(1)


class PlaySigningIsolationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(dir=ROOT / "build")
        cls.stores = {}
        for name in ("existing", "play"):
            path = Path(cls.temporary.name) / f"{name}.jks"
            subprocess.run(
                ["keytool", "-genkeypair", "-keystore", str(path), "-storepass", PASSWORD,
                 "-keypass", PASSWORD, "-alias", name, "-dname", f"CN={name}",
                 "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-noprompt"],
                check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            cls.stores[name] = path

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def report(self, play):
        env = os.environ.copy()
        for key in list(env):
            if key.startswith("AND_CODE_STORE_") or key.startswith("AND_CODE_KEY_") or key.startswith("AND_CODE_PLAY_"):
                env.pop(key)
        env.update(AND_CODE_STORE_FILE=str(self.stores["existing"]), AND_CODE_STORE_PASSWORD=PASSWORD,
                   AND_CODE_KEY_ALIAS="existing", AND_CODE_KEY_PASSWORD=PASSWORD)
        if play:
            env.update(AND_CODE_PLAY_STORE_FILE=str(self.stores["play"]), AND_CODE_PLAY_STORE_PASSWORD=PASSWORD,
                       AND_CODE_PLAY_KEY_ALIAS="play", AND_CODE_PLAY_KEY_PASSWORD=PASSWORD)
        return subprocess.run([str(ROOT / "gradlew"), ":app:signingReport", "--offline", "--no-daemon"],
                              cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, check=True).stdout

    def test_existing_release_key_does_not_sign_play(self):
        output = self.report(play=False)
        self.assertEqual("release", signing_config(output, "githubRelease"))
        self.assertEqual("release", signing_config(output, "fdroidRelease"))
        self.assertEqual("null", signing_config(output, "playRelease"))
        self.assertEqual("debug", signing_config(output, "playDebug"))

    def test_play_key_only_signs_play_release(self):
        output = self.report(play=True)
        self.assertEqual("release", signing_config(output, "githubRelease"))
        self.assertEqual("release", signing_config(output, "fdroidRelease"))
        self.assertEqual("playRelease", signing_config(output, "playRelease"))
        self.assertEqual("debug", signing_config(output, "playDebug"))

    def test_incomplete_play_credentials_fail_instead_of_silent_unsigned_build(self):
        env = os.environ.copy()
        for key in list(env):
            if key.startswith("AND_CODE_PLAY_"):
                env.pop(key)
        env["AND_CODE_PLAY_STORE_FILE"] = str(self.stores["play"])
        result = subprocess.run(
            [str(ROOT / "gradlew"), ":app:signingReport", "--offline", "--no-daemon"],
            cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Incomplete Play signing configuration", result.stdout)


if __name__ == "__main__":
    unittest.main()
