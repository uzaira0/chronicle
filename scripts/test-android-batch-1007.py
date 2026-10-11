#!/usr/bin/env python3
"""Offline fixtures for the Android manifest, mapping, signing and operator-script gates."""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'

class AndroidBatch1007Test(unittest.TestCase):
    def test_export_audit_accepts_flavors_and_rejects_unexpected_exports(self):
        common = [('activity', 'MainActivity', None), ('activity', 'Enrollment', None),
                  ('receiver', 'receivers.lifecycle.StartOnBoot', None),
                  ('receiver', 'receivers.lifecycle.DeviceLifecycleReceiver', None),
                  ('service', 'androidx.work.impl.background.systemjob.SystemJobService', 'android.permission.BIND_JOB_SERVICE')]
        research = [('receiver', 'receivers.lifecycle.LockedBootReceiver', None),
                    ('activity', 'HealthConnectRationaleActivity', None),
                    ('activity-alias', 'ViewPermissionUsageActivity', 'android.permission.START_VIEW_PERMISSION_USAGE'),
                    ('service', 'collection.interaction.InteractionCollectionService', 'android.permission.BIND_ACCESSIBILITY_SERVICE')]
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / 'manifest.xml'
            for flavor in ['open', 'research', 'play', 'amazon']:
                root = ET.Element('manifest'); app = ET.SubElement(root, 'application')
                for kind, name, permission in common + (research if flavor in ['open', 'research'] else []):
                    if not name.startswith('androidx.'): name = 'com.openlattice.chronicle.' + name
                    component = ET.SubElement(app, kind, {ANDROID+'name': name, ANDROID+'exported': 'true'})
                    if permission: component.set(ANDROID+'permission', permission)
                # These are present but deliberately private.
                ET.SubElement(app, 'receiver', {ANDROID+'name': 'com.openlattice.chronicle.receivers.lifecycle.UnlockDeviceReceiver', ANDROID+'exported': 'false'})
                ET.ElementTree(root).write(manifest)
                result = subprocess.run(['bash', str(ROOT/'scripts/android-exported-component-audit.sh'), str(manifest), flavor], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stdout+result.stderr)
                ET.SubElement(app, 'receiver', {ANDROID+'name': 'unexpected.ExportedReceiver', ANDROID+'exported': 'true'})
                ET.ElementTree(root).write(manifest)
                result = subprocess.run(['bash', str(ROOT/'scripts/android-exported-component-audit.sh'), str(manifest), flavor], capture_output=True, text=True)
                self.assertEqual(1, result.returncode)
                self.assertIn('unexpected exported component', result.stdout)

    def test_response_dto_mapping_rejects_a_renamed_field(self):
        with tempfile.TemporaryDirectory() as temporary:
            mapping = Path(temporary)/'mapping.txt'
            cls = 'com.openlattice.chronicle.api.EnrollmentResponse'
            for field, expected in [('apiKey', 0), ('a', 1)]:
                mapping.write_text(f'{cls} -> {cls}:\n    java.lang.String apiKey -> {field}\n')
                result = subprocess.run(['bash', str(ROOT/'scripts/verify-wire-dto-names.sh'), str(mapping)], capture_output=True, text=True)
                self.assertEqual(expected, result.returncode, result.stdout+result.stderr)
                if expected: self.assertIn('reflective JSON field names renamed', result.stderr)

    def test_external_signing_path_does_not_require_a_tree_local_file(self):
        source = (ROOT/'app/build.gradle').read_text()
        self.assertIn("gradleProperty('chronicleSigningProperties')", source)
        self.assertIn("environmentVariable('CHRONICLE_SIGNING_PROPERTIES')", source)
        self.assertIn('file(signingPropertiesPath)', source)
        gate = (ROOT/'scripts/android-release-candidate-gate.sh').read_text()
        assignment = next(line for line in gate.splitlines() if line.startswith('signing_file='))
        with tempfile.TemporaryDirectory() as temporary:
            external = Path(temporary)/'external-public-fixture.props'
            external.write_text('storeFile=synthetic-store\nkeyAlias=fixture-release\n')
            result = subprocess.run(['bash', '-c', assignment+'\n[[ -f "$signing_file" ]]'], env=dict(os.environ, ROOT_DIR=str(Path(temporary)/'absent-tree'), CHRONICLE_SIGNING_PROPERTIES=str(external)), capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_candidate_lint_failure_prevents_assembly(self):
        source = (ROOT/'scripts/android-release-candidate-gate.sh').read_text()
        lines = source.splitlines()
        start = next(i for i, line in enumerate(lines) if './gradlew :app:lintOpenRelease "$assemble_task"' in line)
        invocation = '\n'.join(lines[start:start + 2])  # the gradle call pipes into tee on the next line
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'gradlew').write_text('#!/bin/bash\n[[ "$1" == :app:lintOpenRelease ]] || exit 99\nexit 42\n')
            (root/'gradlew').chmod(0o755)
            result = subprocess.run(['bash', '-c', 'set -euo pipefail\n'+invocation], env=dict(os.environ, ROOT_DIR=temporary, assemble_task=':app:assemblePlayRelease', output_dir=temporary, variant='playRelease'), capture_output=True, text=True)
            self.assertEqual(42, result.returncode, result.stdout+result.stderr)

    def test_debug_driver_restores_original_state_after_failure_and_termination(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); adb = root/'adb'; state = root/'enabled'; pid = root/'pid'
            adb.write_text('''#!/usr/bin/env python3
import json, os, pathlib, signal, sys, time
args=sys.argv[1:]; state=pathlib.Path(os.environ['FIXTURE_STATE']); enabled=state.read_text()=='true'
disabled=pathlib.Path(os.environ['FIXTURE_DISABLED'])
if 'getconf' in args: print('4096')
elif 'path' in args: print('package:/data/app/base.apk')
elif 'broadcast' in args:
    action=args[args.index('-a')+1]
    if action.endswith('SET_SERVER_ENABLED'):
        state.write_text(args[args.index('--ez')+2])
        if state.read_text()=='false': disabled.touch()
        print('Broadcast completed: result=-1')
    elif action.endswith('DUMP_LOCAL_STATE'):
        print('Broadcast completed: result=-1, data="'+json.dumps({'servers':[{'id':1,'enabled':enabled}]})+'"')
    elif action.endswith('ASSERT_SINGLE_DESTINATION') and args[-1]=='0' and disabled.exists():
        if os.environ['FIXTURE_MODE']=='term':
            pid=pathlib.Path(os.environ['FIXTURE_PID'])
            for _ in range(100):
                if pid.exists(): break
                time.sleep(.01)
            os.kill(int(pid.read_text()), signal.SIGTERM)
        print('Broadcast completed: result=0')
    else: print('Broadcast completed: result=-1')
''')
            adb.chmod(0o755)
            for original, mode in [('true', 'failure'), ('false', 'failure'), ('true', 'term')]:
                state.write_text(original); pid.unlink(missing_ok=True)
                disabled = root/'disabled'; disabled.unlink(missing_ok=True)
                env = dict(os.environ, PATH=str(root)+os.pathsep+os.environ['PATH'], REPO_PREFLIGHT=str(root/'missing-preflight'), FIXTURE_STATE=str(state), FIXTURE_MODE=mode, FIXTURE_PID=str(pid), FIXTURE_DISABLED=str(disabled))
                process = subprocess.Popen(['bash', str(ROOT/'scripts/android-debug-validation-driver.sh'), '--serial', 'fixture', '--server-id', '1', '--skip-pull-apk', '--output-dir', str(root/'evidence')], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                pid.write_text(str(process.pid)); stdout, stderr = process.communicate(timeout=10)
                self.assertNotEqual(0, process.returncode, stdout+stderr)
                self.assertEqual(original, state.read_text(), stdout+stderr)
                self.assertTrue((root/'evidence/restore-server.txt').exists(), stdout+stderr)

    def test_upgrade_drill_requires_supplied_versions_and_checks_single_delivery(self):
        source = (ROOT/'app/src/androidTest/java/com/openlattice/chronicle/compat/ApkUpgradeMigrationTest.kt').read_text()
        self.assertIn('requiredVersion(PREDECESSOR_VERSION_ARGUMENT)', source)
        self.assertIn('requiredVersion(CURRENT_VERSION_ARGUMENT)', source)
        self.assertNotIn('installedVersionCode() == 48L', source)
        self.assertIn('assertEquals(1, posts)', source)

    def test_store_draft_discloses_registry_retention_and_device_settings(self):
        draft = (ROOT/'store/play/data-safety.md').read_text()
        self.assertNotIn('30 days', draft)
        self.assertNotIn('Required in the submitted Console declaration', draft)
        for field in ['device-settings', 'accessibility', 'location-services', 'free/total storage', 'study’s data-retention period']:
            self.assertIn(field, draft)
        self.assertIn('legal_copy_status=pending_owner_approval', (ROOT/'store/play/privacy.properties').read_text())

if __name__ == '__main__': unittest.main()
