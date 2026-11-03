#!/usr/bin/env python3
"""Behavior checks for manifest, signing, upload and installed-binary gates."""

import contextlib
import io
import json
import re
import runpy
import subprocess
import sys
import tempfile
import types
import unittest
import zipfile
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("play-upload.py")
GATE = runpy.run_path(str(SCRIPT))
GLOBALS = GATE["check"].__globals__
SIGNER = "AB" * 32
PERMISSION = "android.permission.INTERNET"
SPECIAL_SERVICE = "com.bcm.chronicle.Monitor:fgs-specialUse"
BADGING = f"package: name='{GATE['PACKAGE']}' versionCode='70' versionName='release.70'"
PERMISSIONS = f"uses-permission: name='{PERMISSION}'\n"
XMLTREE = '''E: manifest (line=1)
  E: application (line=2)
    E: service (line=3)
      A: android:name(0x01010003)=".Monitor" (Raw: ".Monitor")
      A: android:foregroundServiceType(0x01010599)=0x40000000 (Raw: "specialUse")
      E: property (line=4)
        A: android:name(0x01010003)="nested.property"
'''
CERTIFICATE = f"Signer #1:\nCertificate #1:\n SHA256: {':'.join([SIGNER[i:i+2] for i in range(0, 64, 2)])}\n"
REGISTRY_PATH, _ = GATE["SUBMISSION"]["registry_contract"](
    GATE["SUBMISSION"]["read_properties"](GATE["ROOT"] / "store/play/privacy.properties"))
REGISTRY = GATE["ROOT"] / REGISTRY_PATH
REGISTRY_HASH = GATE["SUBMISSION"]["sha256"](REGISTRY)
CANDIDATE = "synthetic-candidate-v70-approved"
XMLTREE += "".join(
    f'    E: meta-data\n      A: android:name="{name}"\n      A: android:value="{value}"\n'
    for name, key in GATE["RELEASE_METADATA"].items()
    for value in [CANDIDATE if key == "release_candidate_id" else REGISTRY_HASH]
)
JARSIGNER_SELF_SIGNED = '''jar verified, with signer errors.
Error:
This jar contains entries whose certificate chain is invalid. Reason: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
This jar contains entries whose signer certificate is self-signed.
Warning:
This jar contains signatures that do not include a timestamp. Without a timestamp, users may not be able to validate this jar after any of the signer certificates expire (as early as 2051-08-18).
POSIX file permission and/or symlink attributes detected. These attributes are ignored when signing and are not protected by the signature.
Re-run with the -verbose and -certs options for more details.
'''

# Every AGP-built AAB: manifest written last, invisible to a streaming reader.
STREAM_ONLY = """This JAR file contains internal inconsistencies that may result in different contents when reading via JarFile and JarInputStream:
- Manifest is missing when reading via JarInputStream
- Entry base/dex/classes.dex is signed in JarFile but is not signed in JarInputStream
"""

LOCKED = dict(re.findall(r"^([A-Za-z0-9_.-]+)==([^ ;\\]+)", SCRIPT.with_name("play-upload.requirements.txt").read_text(),
                         re.MULTILINE))


def write_sbom(aab, digest=None):
    content = digest or GATE["SUBMISSION"]["sha256"](aab)
    aab.with_suffix(".cdx.json").write_text(json.dumps({
        "bomFormat": "CycloneDX",
        "metadata": {"component": {"hashes": [{"alg": "SHA-256", "content": content}]}},
        "components": [{"purl": "pkg:maven/androidx.core/core@1.0.0"}],
    }), encoding="utf-8")


class PlayUploadTests(unittest.TestCase):
    def setUp(self):
        self.properties = {
            "package_name": GATE["PACKAGE"], "version_code": "70", "version_name": "release.70",
            GATE["MAXIMUM_UPLOADED_VERSION_CODE"]: "69", "declared_permissions": PERMISSION,
            "declared_services": SPECIAL_SERVICE, "upload_certificate_sha256": SIGNER,
            "play_app_signing_certificate_sha256": SIGNER,
            "play_variant": "openRelease", "module_registry_path": REGISTRY_PATH,
            "approved_module_registry_sha256": REGISTRY_HASH, "release_candidate_id": CANDIDATE,
        }

    def manifest(self, permissions=PERMISSIONS):
        return {**GATE["parse_manifest"](BADGING, permissions, XMLTREE),
                "packaged_registry_sha256": REGISTRY_HASH}

    def errors(self, badging=BADGING, permissions=PERMISSIONS, xmltree=XMLTREE, certificate=CERTIFICATE):
        return GATE["compare_contract"](
            GATE["parse_manifest"](badging, permissions, xmltree),
            GATE["parse_signers"](certificate), self.properties,
        )

    def test_exact_match_passes(self):
        self.assertEqual([], self.errors())

    def test_missing_permission_fails(self):
        self.assertIn(f"missing permissions: {PERMISSION}", self.errors(permissions=""))

    def test_extra_permission_fails(self):
        extra = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        self.assertIn(f"extra permissions: {extra}", self.errors(
            permissions=PERMISSIONS + f"uses-permission: name='{extra}'\n"))

    def test_extra_service_fails(self):
        xmltree = XMLTREE + '''    E: service (line=5)
      A: android:name=".Listener"
      A: android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
'''
        self.assertIn("extra services: com.bcm.chronicle.Listener:notification_listener", self.errors(xmltree=xmltree))

    def test_unrecognized_bind_services_must_be_declared_but_job_services_are_exempt(self):
        for name in (".Vpn", "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService",
                     "androidx.work.impl.foreground.SystemForegroundService"):
            with self.subTest(name=name):
                xmltree = XMLTREE + f'''    E: service (line=5)
      A: android:name="{name}"
'''
                self.assertEqual([], self.errors(xmltree=xmltree))
                self.assertEqual([], self.errors(xmltree=xmltree +
                    '      A: android:permission="android.permission.BIND_JOB_SERVICE"\n'))
                xmltree += '      A: android:permission="android.permission.BIND_VPN_SERVICE"\n'
                service = f"{GATE['PACKAGE'] + name if name.startswith('.') else name}:bind-VPN_SERVICE"
                self.assertIn(f"extra services: {service}", self.errors(xmltree=xmltree))
                original = self.properties["declared_services"]
                self.properties["declared_services"] += f",{service}"
                self.assertEqual([], self.errors(xmltree=xmltree))
                self.properties["declared_services"] = original

    def test_release_candidate_id_must_name_the_version_code(self):
        self.properties["release_candidate_id"] = "synthetic-candidate-v69-approved"
        self.assertIn("release_candidate_id must name v70", self.errors())

    def test_wrong_signer_fails(self):
        errors = self.errors(certificate=CERTIFICATE.replace("AB", "CD"))
        self.assertTrue(any("upload_certificate_sha256" in error for error in errors))

    def test_stale_version_code_fails_and_reports_both_version_mismatches(self):
        errors = self.errors(badging=BADGING.replace("versionCode='70'", "versionCode='69'"))
        self.assertIn("version_code: expected 70, actual 69", errors)
        self.assertTrue(any("must exceed" in error for error in errors))

    def test_wrong_identity_and_version_name_fail(self):
        errors = self.errors(badging=BADGING.replace(GATE["PACKAGE"], "com.example.other").replace("release.70", "other"))
        self.assertTrue(any("package_name: expected" in error for error in errors))
        self.assertIn("version_name: expected release.70, actual other", errors)

    def test_max_sdk_qualifier_is_part_of_permission_contract(self):
        permission = "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
        self.properties["declared_permissions"] += f",{permission}@maxSdk=28"
        self.assertEqual([], self.errors(permissions=PERMISSIONS + f"uses-permission: name='{permission}' maxSdkVersion='28'\n"))
        errors = self.errors(permissions=PERMISSIONS + f"uses-permission: name='{permission}'\n")
        self.assertIn(f"missing permissions: {permission}@maxSdk=28", errors)
        self.assertIn(f"extra permissions: {permission}", errors)

    def test_all_foreground_service_types_and_accessibility_binding_are_checked(self):
        self.properties["declared_services"] += ",com.bcm.chronicle.Access:accessibility,com.bcm.chronicle.Access:fgs-dataSync,com.bcm.chronicle.Access:fgs-mediaPlayback"
        xmltree = XMLTREE + '''    E: service (line=5)
      A: android:name="Access"
      A: android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
      A: android:foregroundServiceType="dataSync|mediaPlayback"
'''
        self.assertEqual([], self.errors(xmltree=xmltree))
        self.assertIn(f"missing services: {SPECIAL_SERVICE}", self.errors(xmltree=xmltree.replace(XMLTREE, "")))

    def test_compiled_service_attributes_override_raw_labels(self):
        self.assertEqual([], self.errors(xmltree=XMLTREE.replace('(Raw: ".Monitor")', '(Raw: ".Other")')))
        errors = self.errors(xmltree=XMLTREE.replace("=0x40000000", "=0x00000001"))
        self.assertIn("extra services: com.bcm.chronicle.Monitor:fgs-dataSync", errors)
        self.assertIn(f"missing services: {SPECIAL_SERVICE}", errors)
        self.assertEqual([], self.errors(xmltree=XMLTREE.replace('(Raw: "specialUse")', '')))

    def test_unsigned_or_unparseable_manifests_fail_closed(self):
        for certificate in ("Not a signed jar file", "Signer #1:\nSHA256: AA"):
            with self.subTest(certificate=certificate), self.assertRaises(ValueError):
                GATE["parse_signers"](certificate)
        with self.assertRaises(ValueError):
            GATE["parse_manifest"]("", PERMISSIONS, XMLTREE)
        with self.assertRaises(ValueError):
            GATE["parse_manifest"](BADGING, PERMISSIONS, XMLTREE.replace("=0x40000000", "=0x01000000"))

    def test_issuer_certificate_is_not_a_second_signer_but_extra_signer_is_rejected(self):
        chain = CERTIFICATE + "Certificate #2:\nSHA256: " + "CD" * 32 + "\n"
        self.assertEqual([], self.errors(certificate=chain))
        additional_signer = CERTIFICATE.replace("Signer #1", "Signer #2").replace("AB", "CD")
        self.assertTrue(self.errors(certificate=chain + additional_signer))

    def test_aab_signers_require_strict_verification_and_only_known_warnings(self):
        cases = [
            ("jar verified.\n", 0, True),
            (JARSIGNER_SELF_SIGNED, GATE["JARSIGNER_CERTIFICATE_ERROR"], True),
            ("jar verified.\nWarning:\nThis jar contains unsigned entries which have not been integrity-checked.\n", 0, False),
            ("jar verified.\nWarning:\nThis jar contains unsigned entries which have not been integrity-checked.\n", 16, False),
            ("jar verified.\nWarning:\nSome entries are not signed.\n", 0, False),
            ("jar is unsigned.\n", 0, False),
            ("jar verified.\nWarning:\nThis jar contains entries whose signer certificate has expired.\n", 0, False),
            (JARSIGNER_SELF_SIGNED, 8, False),
            (JARSIGNER_SELF_SIGNED + STREAM_ONLY, GATE["JARSIGNER_CERTIFICATE_ERROR"], True),
            (JARSIGNER_SELF_SIGNED + STREAM_ONLY.replace("signed in JarFile but is not signed in JarInputStream",
                                                         "signed in JarInputStream but is not signed in JarFile"),
             GATE["JARSIGNER_CERTIFICATE_ERROR"], False),
            (JARSIGNER_SELF_SIGNED + STREAM_ONLY + "This jar contains unsigned entries which have not been integrity-checked.\n",
             GATE["JARSIGNER_CERTIFICATE_ERROR"], False),
        ]
        for output, status, accepted in cases:
            with self.subTest(output=output, status=status):
                def reply(*command):
                    if command[0] == "keytool":
                        return CERTIFICATE
                    if status:
                        raise subprocess.CalledProcessError(status, command, output=output)
                    return output

                runner = mock.Mock(side_effect=reply)
                aab = Path("synthetic.aab")
                with mock.patch.dict(GLOBALS, run=runner):
                    if accepted:
                        self.assertEqual({SIGNER}, GATE["signer_certificates"](aab))
                    else:
                        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                            GATE["signer_certificates"](aab)
                self.assertTrue(any(call.args[0] == "jarsigner" and "-verify" in call.args
                                    and "-strict" in call.args and str(aab) in call.args
                                    for call in runner.call_args_list))

    @contextlib.contextmanager
    def fixture(self):
        with tempfile.TemporaryDirectory(prefix="play-upload-test-") as temp:
            root = Path(temp)
            path = root / "store/play/privacy.properties"
            path.parent.mkdir(parents=True)
            path.write_text("# keep owner approval\napproved_by=synthetic-owner\n" + "".join(
                f"{key}={value}\n" for key, value in self.properties.items()), encoding="utf-8")
            registry = root / REGISTRY_PATH
            registry.parent.mkdir(parents=True)
            registry.write_bytes(REGISTRY.read_bytes())
            yield root, path

    def test_check_binds_fake_bundle_registry_and_release_metadata_before_upload(self):
        for mismatch in (None, "registry", "missing_registry", "duplicate_registry", "registry_metadata", "candidate", "missing_metadata", "duplicate_metadata", "checkout"):
            with self.subTest(mismatch=mismatch), self.fixture() as (root, _):
                aab = root / "synthetic.aab"
                with zipfile.ZipFile(aab, "w") as bundle:
                    bundle.writestr("base/manifest/AndroidManifest.xml", b"synthetic protobuf")
                    if mismatch != "missing_registry":
                        bundle.writestr(GATE["REGISTRY_ASSET"], b"obsolete registry" if mismatch == "registry" else REGISTRY.read_bytes())
                    if mismatch == "duplicate_registry":
                        with self.assertWarns(UserWarning):
                            bundle.writestr(GATE["REGISTRY_ASSET"], REGISTRY.read_bytes())
                write_sbom(aab)
                tree = XMLTREE
                if mismatch == "registry_metadata":
                    tree = tree.replace(REGISTRY_HASH, "00" * 32)
                elif mismatch == "candidate":
                    tree = tree.replace(CANDIDATE, "UNSEALED")
                elif mismatch == "missing_metadata":
                    tree = "E: manifest"
                elif mismatch == "duplicate_metadata":
                    tree += tree[tree.index("    E: meta-data"):]
                elif mismatch == "checkout":
                    (root / REGISTRY_PATH).write_bytes(b"unapproved checkout registry")
                output = io.StringIO()
                with mock.patch.dict(GLOBALS, sdk_tool=mock.Mock(return_value="aapt2"),
                                     run=mock.Mock(side_effect=[BADGING, PERMISSIONS, tree]),
                                     signer_certificates=mock.Mock(return_value={SIGNER})), \
                     mock.patch.dict(GATE["SUBMISSION"], verify=mock.Mock()), contextlib.redirect_stdout(output):
                    if mismatch is None:
                        GATE["check"](aab, root)
                    else:
                        with self.assertRaises(SystemExit) as failure:
                            GATE["check"](aab, root)
                        self.assertEqual(1, failure.exception.code)
                        self.assertRegex(output.getvalue(), "registr(y|ies)|metadata|candidate")

    def test_check_reports_manifest_signer_and_sealed_contract_failures_together(self):
        manifest = self.manifest(permissions="")
        with self.fixture() as (root, _):
            aab = root / "synthetic.aab"
            aab.write_bytes(b"synthetic bundle")
            output = io.StringIO()
            with mock.patch.dict(GLOBALS, bundle_manifest=mock.Mock(return_value=manifest),
                                 signer_certificates=mock.Mock(return_value={"CD" * 32})), \
                 mock.patch.dict(GATE["SUBMISSION"], verify=mock.Mock(side_effect=SystemExit("unsealed"))), \
                 contextlib.redirect_stdout(output), self.assertRaises(SystemExit) as failure:
                GATE["check"](aab, root)
            self.assertEqual(1, failure.exception.code)
            self.assertIn("missing permissions", output.getvalue())
            self.assertIn("upload_certificate_sha256", output.getvalue())
            self.assertIn("submission: unsealed", output.getvalue())

    def test_check_success_requires_sealed_verification_and_prints_bundle_digest(self):
        with self.fixture() as (root, _):
            aab = root / "synthetic.aab"
            aab.write_bytes(b"synthetic bundle")
            write_sbom(aab)
            output = io.StringIO()
            sealed = mock.Mock()
            with mock.patch.dict(GLOBALS, bundle_manifest=mock.Mock(return_value=self.manifest()),
                                 signer_certificates=mock.Mock(return_value={SIGNER})), \
                 mock.patch.dict(GATE["SUBMISSION"], verify=sealed), contextlib.redirect_stdout(output):
                _, digest = GATE["check"](aab, root)
            sealed.assert_called_once_with(root)
            self.assertIn(f"AAB sha256: {digest}", output.getvalue())

    def test_check_refuses_any_privacy_properties_change_during_verification(self):
        for changed in (b"# changed approval\n", None):
            with self.subTest(changed=changed), self.fixture() as (root, path):
                aab = root / "synthetic.aab"
                aab.write_bytes(b"synthetic bundle")
                original = path.read_bytes()

                def verify(*args, **kwargs):
                    if changed is None:
                        path.unlink()
                    else:
                        path.write_bytes(original + changed)

                output = io.StringIO()
                with mock.patch.dict(GLOBALS, bundle_manifest=mock.Mock(return_value=self.manifest()),
                                     signer_certificates=mock.Mock(return_value={SIGNER})), \
                     mock.patch.dict(GATE["SUBMISSION"], verify=verify), \
                     contextlib.redirect_stdout(output), self.assertRaises(SystemExit) as failure:
                    GATE["check"](aab, root)
                self.assertEqual(1, failure.exception.code)
                self.assertIn("privacy.properties changed during verification", output.getvalue())

    def test_check_parses_the_initial_bytes_even_if_live_properties_change_before_parsing(self):
        with self.fixture() as (root, path):
            aab = root / "synthetic.aab"
            aab.write_bytes(b"synthetic bundle")
            write_sbom(aab)
            original = path.read_bytes()
            read_properties = GATE["SUBMISSION"]["read_properties"]

            def read_snapshot(snapshot_path):
                path.write_bytes(original.replace(b"version_code=70", b"version_code=71"))
                try:
                    return read_properties(snapshot_path)
                finally:
                    path.write_bytes(original)

            with mock.patch.dict(GLOBALS, bundle_manifest=mock.Mock(return_value=self.manifest()),
                                 signer_certificates=mock.Mock(return_value={SIGNER})), \
                 mock.patch.dict(GATE["SUBMISSION"], read_properties=read_snapshot, verify=mock.Mock()), \
                 contextlib.redirect_stdout(io.StringIO()):
                properties, _ = GATE["check"](aab, root)
            self.assertEqual("70", properties["version_code"])

    def test_check_requires_the_sbom_bound_to_the_exact_bundle(self):
        for sbom in ("missing", "other_bundle"):
            with self.subTest(sbom=sbom), self.fixture() as (root, _):
                aab = root / "synthetic.aab"
                aab.write_bytes(b"synthetic bundle")
                if sbom == "other_bundle":
                    write_sbom(aab, digest="00" * 32)
                output = io.StringIO()
                with mock.patch.dict(GLOBALS, bundle_manifest=mock.Mock(return_value=self.manifest()),
                                     signer_certificates=mock.Mock(return_value={SIGNER})), \
                     mock.patch.dict(GATE["SUBMISSION"], verify=mock.Mock()), \
                     contextlib.redirect_stdout(output), self.assertRaises(SystemExit):
                    GATE["check"](aab, root)
                self.assertIn("SBOM synthetic.cdx.json", output.getvalue())

    def test_feature_module_permissions_are_also_compared(self):
        extra = "android.permission.RECORD_AUDIO"
        with self.fixture() as (root, _):
            aab = root / "feature.aab"
            with zipfile.ZipFile(aab, "w") as bundle:
                for module in ("base", "feature"):
                    bundle.writestr(f"{module}/manifest/AndroidManifest.xml", b"synthetic protobuf")
                bundle.writestr(GATE["REGISTRY_ASSET"], REGISTRY.read_bytes())
            replies = [BADGING, PERMISSIONS, XMLTREE, BADGING,
                       f"uses-permission: name='{extra}'", "E: manifest"]
            with mock.patch.dict(GLOBALS, run=mock.Mock(side_effect=replies), sdk_tool=mock.Mock(return_value="aapt2")):
                manifest = GATE["bundle_manifest"](aab)
            self.assertIn(f"extra permissions: {extra}", GATE["compare_contract"](manifest, {SIGNER}, self.properties))

    def test_upload_refuses_before_importing_google_or_reading_credentials(self):
        with mock.patch.dict(GLOBALS, check=mock.Mock(side_effect=SystemExit(1))), \
             mock.patch.dict(sys.modules, {"google": None, "googleapiclient": None}), \
             self.assertRaises(SystemExit):
            GATE["upload"](Path("unused-service-account.json"), Path("bad.aab"))

    def test_upload_checks_server_digest_version_and_completed_receipt_before_recording(self):
        for outcome in ("digest_mismatch", "version_mismatch", "incomplete", "wrong_receipt_version", "completed"):
            with self.subTest(outcome=outcome), self.fixture() as (root, path):
                original = path.read_text()
                if outcome == "completed":
                    original = original.replace(f"version_name={self.properties['version_name']}", "version_name=unchecked")
                    path.write_text(original)
                edits = mock.MagicMock()
                edits.insert.return_value.execute.side_effect = [{"id": "upload-edit"}, {"id": "receipt-edit"}]
                edits.bundles.return_value.upload.return_value.execute.return_value = {
                    "versionCode": 71 if outcome == "version_mismatch" else 70,
                    "sha256": "bad" if outcome == "digest_mismatch" else "verified-digest",
                }
                edits.tracks.return_value.get.return_value.execute.return_value = {
                    "releases": [{"status": GATE["COMPLETED"] if outcome in ("completed", "wrong_receipt_version") else "draft",
                                  "versionCodes": ["71" if outcome == "wrong_receipt_version" else "70"]}],
                }
                oauth = types.ModuleType("google.oauth2")
                oauth.service_account = mock.MagicMock()
                discovery = types.ModuleType("googleapiclient.discovery")
                discovery.build = mock.Mock(return_value=mock.Mock(edits=mock.Mock(return_value=edits)))
                http = types.ModuleType("googleapiclient.http")
                http.MediaFileUpload = mock.Mock()
                modules = {"google": types.ModuleType("google"), "google.oauth2": oauth,
                           "googleapiclient": types.ModuleType("googleapiclient"),
                           "googleapiclient.discovery": discovery, "googleapiclient.http": http}
                with mock.patch.dict(sys.modules, modules), mock.patch.dict(
                    GLOBALS, ROOT=root, check=mock.Mock(return_value=(self.properties, "verified-digest")),
                    installed_version=LOCKED.get,
                ), contextlib.redirect_stdout(io.StringIO()):
                    if outcome == "completed":
                        GATE["upload"](Path("unused-service-account.json"), root / "checked.aab")
                    else:
                        with self.assertRaises(ValueError):
                            GATE["upload"](Path("unused-service-account.json"), root / "checked.aab")
                if outcome in ("digest_mismatch", "version_mismatch"):
                    edits.commit.assert_not_called()
                    edits.tracks.return_value.update.assert_not_called()
                else:
                    release = edits.tracks.return_value.update.call_args.kwargs["body"]["releases"][0]
                    self.assertEqual(self.properties["version_name"], release["name"])
                    self.assertEqual(GATE["COMPLETED"], release["status"])
                expected = original.replace(f"{GATE['MAXIMUM_UPLOADED_VERSION_CODE']}=69",
                                            f"{GATE['MAXIMUM_UPLOADED_VERSION_CODE']}=70") if outcome == "completed" else original
                self.assertEqual(expected, path.read_text())

    def test_upload_refuses_client_libraries_that_differ_from_the_lock_before_reading_credentials(self):
        oauth = types.ModuleType("google.oauth2")
        oauth.service_account = mock.MagicMock()
        modules = {"google": types.ModuleType("google"), "google.oauth2": oauth}
        drifted = dict(LOCKED, **{"google-auth": "2.0.0"})
        missing = {name: version for name, version in LOCKED.items() if name != "google-api-python-client"}
        for label, installed, message in (
            ("drifted", drifted, "google-auth: installed 2.0.0, locked 2.62.0"),
            ("missing", missing, "google-api-python-client: not installed, locked 2.201.0"),
        ):
            with self.subTest(label), mock.patch.dict(sys.modules, modules), mock.patch.dict(
                GLOBALS, check=mock.Mock(return_value=(self.properties, "verified-digest")), installed_version=installed.get,
            ):
                with self.assertRaises(ValueError) as refused:
                    GATE["upload"](Path("unused-service-account.json"), Path("checked.aab"))
                self.assertIn(message, str(refused.exception))
                oauth.service_account.Credentials.from_service_account_file.assert_not_called()

    def test_active_marker_pins_are_required_and_inactive_pins_may_be_absent(self):
        from packaging.markers import default_environment
        absent = {name: version for name, version in LOCKED.items() if name != "pycparser"}
        environment = default_environment()
        with mock.patch.dict(GLOBALS, installed_version=absent.get), \
             mock.patch('packaging.markers.default_environment', return_value={**environment, 'platform_python_implementation': 'CPython'}), \
             self.assertRaises(ValueError):
            GATE["require_locked_client_libraries"]()
        with mock.patch.dict(GLOBALS, installed_version=absent.get), \
             mock.patch('packaging.markers.default_environment', return_value={**environment, 'platform_python_implementation': 'PyPy'}):
            GATE["require_locked_client_libraries"]()
        with mock.patch.dict(GLOBALS, installed_version=dict(LOCKED, pycparser="1.0").get), \
             self.assertRaises(ValueError):
            GATE["require_locked_client_libraries"]()

    def test_the_upload_lock_is_hash_pinned_and_matches_its_inputs(self):
        lock = SCRIPT.with_name("play-upload.requirements.txt").read_text()
        requirements = [line for line in lock.splitlines() if re.match(r"^[A-Za-z0-9]", line)]
        self.assertTrue(requirements)
        for line in requirements:
            self.assertRegex(line, r"^[A-Za-z0-9_.-]+==[^ ;]+( ;[^\\]+)? \\$", line)
        blocks = re.split(r"\n(?=[A-Za-z0-9])", lock.split("\n", 1)[1] if lock.startswith("#") else lock)
        for block in blocks:
            if re.match(r"^[A-Za-z0-9]", block):
                self.assertIn("--hash=sha256:", block, block.splitlines()[0])
        inputs = SCRIPT.with_name("play-upload.requirements.in").read_text()
        for name, version in re.findall(r"^([A-Za-z0-9_.-]+)==(\S+)$", inputs, re.MULTILINE):
            self.assertEqual(version, LOCKED[name])
        self.assertTrue({"google-auth", "google-api-python-client"} <= set(LOCKED))

    def test_record_uploaded_version_preserves_other_properties_and_never_lowers_the_ceiling(self):
        with self.fixture() as (_, path):
            key = GATE["MAXIMUM_UPLOADED_VERSION_CODE"]
            original = path.read_text().replace(f"{key}=69", f"  {key} =72")
            path.write_text(original)
            GATE["record_uploaded_version"](path, 70)
            self.assertEqual(original.replace(f"  {key} =72", f"{key}=72"), path.read_text())

    def test_installed_binary_requires_play_installer_version_and_app_signing_certificate(self):
        for exact in (True, False):
            with self.subTest(exact=exact), self.fixture() as (root, _):
                replies = [f"versionCode={70 if exact else 69} minSdk=26\ninitiatingPackageName={GATE['PLAY_INSTALLER']}",
                           f"package:{GATE['PACKAGE']} installer={GATE['PLAY_INSTALLER'] if exact else 'com.android.shell'}\n",
                           "package:/data/app/chronicle/base.apk\n", "pulled"]
                output = io.StringIO()
                with mock.patch.dict(GLOBALS, ROOT=root, run=mock.Mock(side_effect=replies),
                                     signer_certificates=mock.Mock(return_value={SIGNER if exact else "CD" * 32})), \
                     mock.patch.object(GATE["shutil"], "which", return_value="adb"), \
                     contextlib.redirect_stdout(output):
                    if exact:
                        GATE["verify_installed"]("synthetic-device")
                    else:
                        with self.assertRaises(SystemExit):
                            GATE["verify_installed"]("synthetic-device")
                if not exact:
                    for mismatch in ("installed version_code", "installed installer", "play_app_signing_certificate_sha256"):
                        self.assertIn(mismatch, output.getvalue())

    def test_installed_play_label_requires_known_play_initiator(self):
        for initiator in (None, "com.android.shell", "null", GATE["PLAY_INSTALLER"]):
            with self.subTest(initiator=initiator), self.fixture() as (root, _):
                package_info = "versionCode=70 minSdk=26\n"
                if initiator is not None:
                    package_info += f"initiatingPackageName={initiator}\n"
                replies = [package_info, f"package:{GATE['PACKAGE']} installer={GATE['PLAY_INSTALLER']}\n",
                           "package:/data/app/chronicle/base.apk\n", "pulled"]
                output = io.StringIO()
                with mock.patch.dict(GLOBALS, ROOT=root, run=mock.Mock(side_effect=replies),
                                     signer_certificates=mock.Mock(return_value={SIGNER})), \
                     mock.patch.object(GATE["shutil"], "which", return_value="adb"), \
                     contextlib.redirect_stdout(output):
                    if initiator == GATE["PLAY_INSTALLER"]:
                        GATE["verify_installed"]("synthetic-device")
                    else:
                        with self.assertRaises(SystemExit):
                            GATE["verify_installed"]("synthetic-device")
                        self.assertIn("installed initiatingPackageName", output.getvalue())

    def test_bundle_with_the_v69_battery_permission_is_rejected(self):
        extra = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        with self.fixture() as (root, _):
            bundle = root / "legacy-permission.aab"
            bundle.write_bytes(b"synthetic bundle")
            write_sbom(bundle)
            manifest = self.manifest(PERMISSIONS + f"uses-permission: name='{extra}'\n")
            output = io.StringIO()
            with mock.patch.dict(GLOBALS, bundle_manifest=mock.Mock(return_value=manifest),
                                 signer_certificates=mock.Mock(return_value={SIGNER})), \
                 mock.patch.dict(GATE["SUBMISSION"], verify=mock.Mock()), \
                 contextlib.redirect_stdout(output), self.assertRaises(SystemExit) as failure:
                GATE["check"](bundle, root)
            self.assertEqual(1, failure.exception.code)
            self.assertIn(f"extra permissions: {extra}", output.getvalue())


if __name__ == "__main__":
    unittest.main()
