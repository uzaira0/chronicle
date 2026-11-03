"""Exercise Gradle signing admission with synthetic properties and dry-run tasks only."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORK = Path(os.environ["ANDROID_AUDIT_WORK"])


class ReleaseSigningPolicyTests(unittest.TestCase):
    def test_release_rejects_each_debug_signing_input_before_opening_the_store(self):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=WORK) as folder:
            root = Path(folder)
            store = root / "empty.fixture"
            store.touch()
            properties = root / "fixture.properties"
            values = dict(storeFile=str(store), storePassword="fixture-password",
                          keyAlias="fixture-alias", keyPassword="fixture-password")
            cases = (("keyAlias", "androiddebugkey"), ("storeFile", str(root / "debug.keystore")),
                     ("storePassword", "android"), ("keyPassword", "android"))
            for key, value in cases:
                with self.subTest(key=key):
                    if key == "storeFile":
                        Path(value).touch()
                    properties.write_text("".join(f"{name}={item}\n" for name, item in dict(values, **{key: value}).items()))
                    run = self.gradle(properties, ":app:bundleOpenRelease")
                    self.assertNotEqual(0, run.returncode, run.stdout)
                    self.assertIn("Chronicle release builds must not use Android debug signing material", run.stdout)
                    self.assertNotIn("fixture-password", run.stdout)

    def test_debug_tasks_accept_a_missing_signing_properties_file(self):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=WORK) as folder:
            run = self.gradle(Path(folder) / "absent.fixture", ":app:assembleOpenDebug")
            self.assertEqual(0, run.returncode, run.stdout)
            self.assertIn("BUILD SUCCESSFUL", run.stdout)

    def gradle(self, properties, task):
        return subprocess.run([str(ROOT / "gradlew"), "--offline", "--no-daemon", "--console=plain", "--dry-run",
                               "-PchronicleSigningProperties=" + str(properties), task], cwd=ROOT,
                              env=dict(os.environ, CHRONICLE_SIGNING_PROPERTIES=str(properties)),
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=180)


if __name__ == "__main__":
    unittest.main()
