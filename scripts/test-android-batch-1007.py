#!/usr/bin/env python3
"""Offline fixtures for the Android manifest, mapping, signing and operator-script gates."""
import runpy
from pathlib import Path
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
                  ('service', 'androidx.work.impl.background.systemjob.SystemJobService', 'android.permission.BIND_JOB_SERVICE')]
        collectors = [('receiver', 'receivers.lifecycle.LockedBootReceiver', None),
                      ('service', 'collection.interaction.InteractionCollectionService', 'android.permission.BIND_ACCESSIBILITY_SERVICE')]
        health = [('activity', 'HealthConnectRationaleActivity', None),
                  ('activity-alias', 'ViewPermissionUsageActivity', 'android.permission.START_VIEW_PERMISSION_USAGE')]
        exports = {'open': common + collectors[:1], 'research': common + collectors + health, 'amazon': common}
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / 'manifest.xml'
            for flavor, components in exports.items():
                root = ET.Element('manifest'); app = ET.SubElement(root, 'application')
                for kind, name, permission in components:
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
            # The Play build (open) has no Health Connect activity; exporting one is a defect.
            root = ET.Element('manifest'); app = ET.SubElement(root, 'application')
            for kind, name, permission in exports['open'] + health:
                if not name.startswith('androidx.'): name = 'com.openlattice.chronicle.' + name
                component = ET.SubElement(app, kind, {ANDROID+'name': name, ANDROID+'exported': 'true'})
                if permission: component.set(ANDROID+'permission', permission)
            ET.ElementTree(root).write(manifest)
            result = subprocess.run(['bash', str(ROOT/'scripts/android-exported-component-audit.sh'), str(manifest), 'open'], capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertIn('unexpected exported component: activity com.openlattice.chronicle.HealthConnectRationaleActivity', result.stdout)

    def test_response_dto_mapping_rejects_a_renamed_field(self):
        with tempfile.TemporaryDirectory() as temporary:
            mapping = Path(temporary)/'mapping.txt'
            cls = 'com.openlattice.chronicle.api.EnrollmentResponse'
            for field, expected in [('apiKey', 0), ('a', 1)]:
                mapping.write_text(f'{cls} -> {cls}:\n    java.lang.String apiKey -> {field}\n')
                result = subprocess.run(['bash', str(ROOT/'scripts/verify-wire-dto-names.sh'), str(mapping)], capture_output=True, text=True)
                self.assertEqual(expected, result.returncode, result.stdout+result.stderr)
                if expected: self.assertIn('reflective JSON field names renamed', result.stderr)

    def test_mapping_rejects_a_wire_field_whose_type_r8_renamed(self):
        # The checked-class list spans many lines; the type check must parse it with any awk.
        with tempfile.TemporaryDirectory() as temporary:
            mapping = Path(temporary)/'mapping.txt'
            cls = 'com.openlattice.chronicle.api.EnrollmentResponse'
            status = 'com.openlattice.chronicle.api.EnrollmentStatus'
            for renamed, expected in [(status, 0), ('optimized.q', 1)]:
                mapping.write_text(f'{cls} -> {cls}:\n    {status} status -> status\n{status} -> {renamed}:\n')
                result = subprocess.run(['bash', str(ROOT/'scripts/verify-wire-dto-names.sh'), str(mapping)],
                                        capture_output=True, text=True)
                self.assertEqual(expected, result.returncode, result.stdout+result.stderr)
                self.assertNotIn('awk:', result.stderr)
                if expected:
                    self.assertIn(f'{cls}: {status} -> optimized.q', result.stderr)

    def test_room_schema_export_has_one_directory_per_ksp_task(self):
        # Room reads and rewrites the exported schema JSON; variants that share one directory race
        # when Gradle runs their KSP tasks together. Every module that exports a schema must give
        # each KSP task its own room.schemaLocation.
        import re
        exporting = []
        for build in sorted(ROOT.glob('*/build.gradle')):
            sources = (build.parent/'src').rglob('*.kt') if (build.parent/'src').is_dir() else []
            for source in sources:
                for declaration in re.findall(r'@Database\((.*?)\n\)', source.read_text(), re.S):
                    if not re.search(r'exportSchema\s*=\s*false', declaration):
                        exporting.append(build)
        self.assertIn(ROOT/'collection-base/build.gradle', exporting)
        for build in set(exporting):
            text = build.read_text()
            self.assertNotRegex(text, r"arg\(\s*['\"]room\.schemaLocation", build)
            self.assertIn('task.commandLineArgumentProviders.add(new RoomSchemaLocation(', text, build)
            self.assertIn('room-schemas/${task.name}', text, build)

    def test_external_signing_path_does_not_require_a_tree_local_file(self):
        fixture = runpy.run_path(str(ROOT / 'scripts/test-release-gate-evidence.py'))['ReleaseGateEvidenceTests']()
        run = fixture.run_gate()
        self.assertEqual(0, run.result.returncode, run.result.stdout + run.result.stderr)
        self.assertIn(b'required_keys_present=yes', run.evidence['signing-audit.txt'])

    def test_candidate_lint_failure_prevents_assembly(self):
        fixture = runpy.run_path(str(ROOT / 'scripts/test-release-gate-evidence.py'))['ReleaseGateEvidenceTests']()
        run = fixture.run_gate(lint_refused=True)
        self.assertEqual(42, run.result.returncode, run.result.stdout + run.result.stderr)
        self.assertEqual([[':app:lintOpenRelease', ':app:assembleOpenRelease', '--no-daemon', '--console=plain']], run.gradle_calls)
        self.assertEqual([], run.calls)

    def test_store_draft_discloses_registry_retention_and_device_settings(self):
        draft = (ROOT/'store/play/data-safety.md').read_text()
        self.assertNotIn('30 days', draft)
        self.assertNotIn('Required in the submitted Console declaration', draft)
        for field in ['device-settings', 'accessibility', 'location-services', 'free/total storage', 'study’s data-retention period']:
            self.assertIn(field, draft)
        self.assertIn('legal_copy_status=approved', (ROOT/'store/play/privacy.properties').read_text())

if __name__ == '__main__': unittest.main()
