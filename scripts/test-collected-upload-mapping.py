#!/usr/bin/env python3
"""The release mapping gate checks DTOs named by generic upload bodies."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
AUDIO_EVENT_CLASS = 'com.openlattice.chronicle.collection.AndroidAudioContentEvent'
BODY_CLASS = 'com.openlattice.chronicle.services.upload.CollectedUploadBody'


class CollectedUploadMappingTest(unittest.TestCase):
    def check_mapping(self, mapping, expected):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / 'mapping.txt'
            candidate.write_text(mapping)
            result = subprocess.run(['bash', str(ROOT / 'scripts/verify-wire-dto-names.sh'), str(candidate)],
                                    capture_output=True, text=True, check=False)
            self.assertNotIn("SyntaxError", result.stderr)
            self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
            return result

    def test_measured_body_is_transport_and_can_be_renamed(self):
        self.check_mapping(f'{BODY_CLASS} -> optimized.body:\n    okio.ByteString bytes -> a\n', 0)

    def test_metadata_fields_still_cannot_be_renamed(self):
        name = 'com.openlattice.chronicle.api.EnrollmentResponse'
        self.check_mapping(f'{name} -> {name}:\n    java.lang.String apiKey -> a\n', 1)

    def test_collected_row_fields_still_cannot_be_renamed(self):
        result = self.check_mapping(f'{AUDIO_EVENT_CLASS} -> {AUDIO_EVENT_CLASS}:\n    java.lang.String title -> a\n', 1)
        self.assertIn('reflective JSON field names renamed', result.stderr)


if __name__ == '__main__':
    unittest.main()
