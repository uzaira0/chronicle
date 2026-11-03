"""Execute the unchanged release gate against synthetic artifacts and fixture-only device commands."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest

GATE = Path(__file__).with_name("android-release-candidate-gate.sh")
WORK = Path(os.environ.get("ANDROID_AUDIT_WORK", GATE.parents[1] / "app/build/release-audit-tests"))

ADB_FIXTURE = r'''
import json
import os
from pathlib import Path
import shutil
import sys

args = sys.argv[1:]
root = Path(os.environ["FIXTURE_ROOT"])
with (root / "calls.jsonl").open("a") as output:
    output.write(json.dumps(args) + "\n")
if args[:2] != ["-s", "fixture-device"]:
    raise SystemExit("unexpected fixture device selection")
if args[2:] == ["install", "-r", os.environ["FIXTURE_APK"]]:
    print("Success")
elif args[2:] == ["shell", "pm", "path", "com.example.fixture"]:
    print("package:/fixture/base.apk")
elif len(args) == 5 and args[2:4] == ["pull", "/fixture/base.apk"]:
    destination = Path(args[4])
    if destination.parent != root / "evidence":
        raise SystemExit("unexpected fixture pull destination")
    shutil.copyfile(os.environ["FIXTURE_APK"], destination)
elif len(args) == 4 and args[2] == "shell" and args[3].startswith("IFS= read -r invitation; exec am start "):
    invitation = sys.stdin.read().strip()
    if invitation != os.environ["FIXTURE_INVITATION"]:
        raise SystemExit("wrong invitation delivered through stdin")
    (root / "dispatched.txt").write_text(invitation)
    print("untrusted device echo " + invitation)
    raise SystemExit(int(os.environ.get("FIXTURE_DISPATCH_REFUSED", "0")))
elif args[2:] == ["shell", "dumpsys", "window"]:
    print("mCurrentFocus=Window{fixture com.example.fixture/com.openlattice.chronicle.Enrollment} #accessCode="
          + os.environ["FIXTURE_CAPABILITY"])
else:
    raise SystemExit("unexpected fixture adb command")
'''


class ReleaseGateEvidenceTests(unittest.TestCase):
    def run_gate(self, *, length=40, mode=0o600, symlink=False, foreign_owner=False,
                 dispatch_refused=False, signer_leaks=False, lint_refused=False):
        WORK.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=WORK) as folder:
            root = Path(folder)
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copyfile(GATE, scripts / GATE.name)
            tools = root / "tools"
            tools.mkdir()
            apk = root / "app/build/outputs/apk/open/release/app-open-release.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"synthetic-apk")
            apk.with_name("output-metadata.json").write_text('{"applicationId":"com.example.fixture"}')
            store = root / "empty.fixture"
            store.touch()
            properties = root / "fixture.properties"
            properties.write_text(f"storeFile={store}\nstorePassword=fixture-value\nkeyAlias=fixture-release\nkeyPassword=fixture-value\n")
            properties.chmod(0o600)
            gradle = root / "gradlew"
            gradle.write_text(f"#!{sys.executable}\nimport json, os, sys\nfrom pathlib import Path\n"
                              "with (Path(os.environ['FIXTURE_ROOT']) / 'gradle-calls.jsonl').open('a') as output:\n"
                              "    output.write(json.dumps(sys.argv[1:]) + '\\n')\nraise SystemExit(42)\n")
            gradle.chmod(0o700)
            for name in ("android-exported-component-audit.sh", "verify-android-16kb-native-libs.sh"):
                helper = scripts / name
                helper.write_text("#!/bin/sh\nprintf 'fixture verifier passed\\n'\n")
                helper.chmod(0o700)
            adb = tools / "adb"
            adb.write_text(f"#!{sys.executable}\n" + ADB_FIXTURE)
            adb.chmod(0o700)
            signer = tools / "apksigner"
            signer.write_text("#!/bin/sh\nprintf 'Signer certificate DN: CN=Fixture Release\\n'\n"
                              "if [ \"${FIXTURE_SIGNER_LEAKS:-0}\" = 1 ]; then printf '%s\\n' \"$FIXTURE_CAPABILITY\"; fi\n")
            signer.chmod(0o700)
            sleep = tools / "sleep"
            sleep.write_text("#!/bin/sh\nexit 0\n")
            sleep.chmod(0o700)
            capability = "c" * length
            invitation = "chronicle://enroll?studyId=11111111-1111-1111-1111-111111111111#accessCode=" + capability
            invitation_file = root / "invitation.fixture"
            invitation_file.write_text(invitation + "\n")
            invitation_file.chmod(mode)
            if symlink:
                linked = root / "linked.fixture"
                linked.symlink_to(invitation_file)
                invitation_file = linked
            if foreign_owner:
                if os.geteuid() == 0:
                    os.chown(invitation_file, 1, -1)
                else:
                    # Only metadata is reached: the ownership check must reject before any read.
                    invitation_file = next(path for path in (Path("/bin/ls"), Path("/usr/bin/true"))
                                           if path.is_file() and not path.is_symlink() and path.stat().st_uid != os.geteuid())
            environment = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"],
                               REPO_PREFLIGHT=str(root / "absent-preflight"), CHRONICLE_SIGNING_PROPERTIES=str(properties),
                               FIXTURE_ROOT=str(root), FIXTURE_APK=str(apk), FIXTURE_INVITATION=invitation,
                               FIXTURE_CAPABILITY=capability, FIXTURE_DISPATCH_REFUSED=str(int(dispatch_refused)),
                               FIXTURE_SIGNER_LEAKS=str(int(signer_leaks)))
            command = ["bash", str(scripts / GATE.name), "--variant", "openRelease"]
            if not lint_refused:
                command.append("--skip-build")
            command += ["--install",
                       "--serial", "fixture-device", "--output-dir", str(root / "evidence"),
                       "--enrollment-url-file", str(invitation_file)]
            result = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=30)
            evidence = {path.name: path.read_bytes() for path in (root / "evidence").glob("*") if path.is_file()}
            calls = [json.loads(line) for line in (root / "calls.jsonl").read_text().splitlines()] if (root / "calls.jsonl").exists() else []
            gradle_calls = [json.loads(line) for line in (root / "gradle-calls.jsonl").read_text().splitlines()] if (root / "gradle-calls.jsonl").exists() else []
            dispatched = (root / "dispatched.txt").read_text() if (root / "dispatched.txt").exists() else None
            return SimpleNamespace(result=result, evidence=evidence, calls=calls, gradle_calls=gradle_calls, dispatched=dispatched,
                                   invitation=invitation, capability=capability)

    def test_invitation_dispatch_uses_stdin_and_retained_evidence_is_redacted(self):
        for length in (32, 256):
            with self.subTest(length=length):
                run = self.run_gate(length=length)
                self.assertEqual(0, run.result.returncode, run.result.stdout + run.result.stderr)
                self.assertEqual(run.invitation, run.dispatched)
                self.assertNotIn(run.capability, json.dumps(run.calls))
                self.assertNotIn(run.capability, run.result.stdout + run.result.stderr)
                self.assertTrue(all(run.capability.encode() not in content for content in run.evidence.values()))
                self.assertIn(b"[REDACTED]", run.evidence["window-focus-after-enrollment-url.txt"])
                context = run.evidence["context.txt"]
                for line in (b"enrollment_intent_dispatched=true", b"enrollment_url_retained=false", b"enrollment_credential_retained=false"):
                    self.assertIn(line, context)

    def test_bad_invitation_length_mode_or_symlink_is_rejected_before_dispatch(self):
        for options in ({"length": 31}, {"length": 257}, {"mode": 0o644}, {"symlink": True}, {"foreign_owner": True}):
            with self.subTest(options=options):
                run = self.run_gate(**options)
                self.assertEqual(2, run.result.returncode, run.result.stdout + run.result.stderr)
                self.assertIsNone(run.dispatched)
                self.assertNotIn(run.capability, run.result.stdout + run.result.stderr)
                if options.get("foreign_owner"):
                    self.assertIn("owned by the current user", run.result.stderr)

    def test_a_failed_dispatch_and_a_credential_in_verifier_output_cannot_report_success(self):
        refused = self.run_gate(dispatch_refused=True)
        self.assertEqual(1, refused.result.returncode)
        self.assertNotIn(b"enrollment_intent_dispatched=true", refused.evidence["context.txt"])
        leaked = self.run_gate(signer_leaks=True)
        self.assertEqual(1, leaked.result.returncode)
        self.assertIn("credential appeared in retained release evidence", leaked.result.stderr)


if __name__ == "__main__":
    unittest.main()
