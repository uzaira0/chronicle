#!/usr/bin/env python3
"""Check the Play artifact contract before uploading or verifying a Play installation."""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import os
import re
import runpy
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SUBMISSION = runpy.run_path(str(ROOT / "scripts/verify-play-submission.py"))
MAXIMUM_UPLOADED_VERSION_CODE = "maximum_uploaded_version_code"
TRACK = "internal"
COMPLETED = "completed"
PACKAGE = "com.bcm.chronicle"
PLAY_INSTALLER = "com.android.vending"
JARSIGNER_CERTIFICATE_ERROR = 4
# Observed on v70: the pinned upload certificate is self-signed, has no timestamp,
# and AAB ZIP attributes are outside the signature. Other diagnostics must refuse.
JARSIGNER_ALLOWED_WARNINGS = (
    r"This jar contains entries whose certificate chain is invalid\. Reason: PKIX path building failed: sun\.security\.provider\.certpath\.SunCertPathBuilderException: unable to find valid certification path to requested target",
    r"This jar contains entries whose signer certificate is self-signed\.",
    r"This jar contains signatures that do not include a timestamp\. Without a timestamp, users may not be able to validate this jar after any of the signer certificates expire \(as early as \d{4}-\d{2}-\d{2}\)\.",
    r"POSIX file permission and/or symlink attributes detected\. These attributes are ignored when signing and are not protected by the signature\.",
    # AGP/bundletool write META-INF/MANIFEST.MF after the payload, so a streaming JarInputStream
    # never sees it. Signatures are checked through the central directory (JarFile), which Play uses;
    # an entry unsigned in that view is still refused ("unsigned entries" / "not signed in JarFile").
    r"This JAR file contains internal inconsistencies that may result in different contents when reading via JarFile and JarInputStream:",
    r"- Manifest is missing when reading via JarInputStream",
    r"- Entry \S+ is signed in JarFile but is not signed in JarInputStream",
)
SERVICE_BINDINGS = {
    "android.permission.BIND_ACCESSIBILITY_SERVICE": "accessibility",
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE": "notification_listener",
}
# Android 36 attrs_manifest.xml flags; compare compiled values rather than Raw labels.
FOREGROUND_SERVICE_TYPES = {
    0x01: "dataSync", 0x02: "mediaPlayback", 0x04: "phoneCall", 0x08: "location",
    0x10: "connectedDevice", 0x20: "mediaProjection", 0x40: "camera", 0x80: "microphone",
    0x100: "health", 0x200: "remoteMessaging", 0x400: "systemExempted", 0x800: "shortService",
    0x2000: "mediaProcessing", 0x40000000: "specialUse",
}
RELEASE_METADATA = {
    "com.bcm.chronicle.RELEASE_CANDIDATE_ID": "release_candidate_id",
    "com.bcm.chronicle.APPROVED_MODULE_REGISTRY_SHA256": "approved_module_registry_sha256",
}
REGISTRY_ASSET = "base/assets/approved-module-registry.json"
# The upload command's Google client libraries, hash-pinned with their whole closure.
CLIENT_LOCK = Path(__file__).with_name("play-upload.requirements.txt")


def installed_version(distribution: str) -> str | None:
    try:
        return importlib.metadata.version(distribution)
    except importlib.metadata.PackageNotFoundError:
        return None


def require_locked_client_libraries() -> None:
    """Refuse to upload with client libraries other than the committed hash-pinned lock.

    A pin may be absent only when its environment marker excludes this interpreter.
    """
    from packaging.requirements import Requirement

    errors = []
    for line in CLIENT_LOCK.read_text().splitlines():
        if not re.match(r'^[A-Za-z0-9]', line):
            continue
        requirement = Requirement(line.rstrip('\\').strip())
        name = requirement.name
        version = next(iter(requirement.specifier)).version
        required = requirement.marker is None or requirement.marker.evaluate()
        installed = installed_version(name)
        if installed is None and required:
            errors.append(f"{name}: not installed, locked {version}")
        elif installed is not None and installed != version:
            errors.append(f"{name}: installed {installed}, locked {version}")
    if errors:
        raise ValueError("Play upload client libraries differ from chronicle/scripts/play-upload.requirements.txt "
                         "(install it into a dedicated virtualenv with uv pip sync or pip --require-hashes): "
                         + "; ".join(errors))


def run(*command: str) -> str:
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT,
                                   env={**os.environ, "LC_ALL": "C"}, timeout=120)


def sdk_tool(name: str) -> str:
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / ".local/android-sdk")))
    candidates = list((sdk / "build-tools").glob(f"*/{name}"))
    if not candidates:
        raise ValueError(f"{name} not found under {sdk / 'build-tools'}")
    return str(max(candidates, key=lambda path: (
        tuple(int(part) for part in re.findall(r"\d+", path.parent.name)),
        "-" not in path.parent.name,
    )))


def xml_elements(output: str):
    """Yield each xmltree element's own attributes, excluding nested child attributes."""
    element, attributes = None, {}
    for line in output.splitlines():
        match = re.match(r"\s*E: ([\w-]+)(?:\s|$)", line)
        if match:
            if element is not None:
                yield element, attributes
            element, attributes = match[1], {}
            continue
        match = re.match(r"\s*A: (?:http://schemas\.android\.com/apk/res/android:|android:)(\w+)(?:\([^)]*\))?=(.*)", line)
        if match and element is not None:
            value = match[2].strip()
            raw = re.search(r'\(Raw: ("(?:\\.|[^"\\])*")\)$', value)
            quoted = re.match(r'("(?:\\.|[^"\\])*")', value)
            numeric = re.match(r"(?:\(type 0x[0-9a-fA-F]+\))?(0x[0-9a-fA-F]+|\d+)\b", value)
            if match[1] == "foregroundServiceType" and numeric:
                mask = int(numeric[1], 16 if numeric[1].startswith("0x") else 10)
                if not mask or mask & ~sum(FOREGROUND_SERVICE_TYPES):
                    raise ValueError(f"unrecognized foregroundServiceType mask: {numeric[1]}")
                value = "|".join(name for bit, name in FOREGROUND_SERVICE_TYPES.items() if mask & bit)
            elif quoted or raw:
                value = json.loads((quoted or raw)[1])
            attributes[match[1]] = value
    if element is not None:
        yield element, attributes


def parse_manifest(badging: str, permissions: str, xmltree: str) -> dict:
    identity = re.search(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'",
        badging, re.MULTILINE,
    )
    if identity is None:
        raise ValueError("aapt2 did not report package, versionCode and versionName")
    declared_permissions = set()
    for line in permissions.splitlines():
        if not line.startswith("uses-permission"):
            continue
        name = re.search(r"name='([^']+)'", line)
        if name is None:
            raise ValueError(f"unrecognized uses-permission: {line}")
        maximum = re.search(r"maxSdkVersion='(\d+)'", line)
        declared_permissions.add(name[1] + (f"@maxSdk={maximum[1]}" if maximum else ""))
    services = set()
    metadata = {}
    for element, attributes in xml_elements(xmltree):
        if element == "meta-data" and attributes.get("name") in RELEASE_METADATA:
            key = RELEASE_METADATA[attributes["name"]]
            if key in metadata:
                raise ValueError(f"duplicate release metadata: {key}")
            metadata[key] = attributes.get("value")
        if element != "service":
            continue
        kinds = []
        permission = attributes.get("permission", "")
        binding = SERVICE_BINDINGS.get(permission)
        if binding:
            kinds.append(binding)
        elif (suffix := permission.removeprefix("android.permission.BIND_")) != permission and suffix != "JOB_SERVICE":
            kinds.append(f"bind-{suffix}")
        if "foregroundServiceType" in attributes:
            types = attributes["foregroundServiceType"].split("|")
            if not all(re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", value.strip()) for value in types):
                raise ValueError("unrecognized foregroundServiceType: " + attributes["foregroundServiceType"])
            kinds.extend(f"fgs-{value.strip()}" for value in types)
        if kinds:
            name = attributes.get("name")
            if not name:
                raise ValueError("special service has no class name")
            if name.startswith("."):
                name = identity[1] + name
            elif "." not in name:
                name = identity[1] + "." + name
            services.update(f"{name}:{kind}" for kind in kinds)
    return {"package_name": identity[1], "version_code": int(identity[2]),
            "version_name": identity[3], "permissions": declared_permissions, "services": services,
            "metadata": metadata}


def bundle_manifest(aab: Path) -> dict:
    aapt2 = sdk_tool("aapt2")
    with zipfile.ZipFile(aab) as source, tempfile.TemporaryDirectory(prefix="play-manifest-") as temp:
        manifests = sorted(name for name in source.namelist() if name.endswith("/manifest/AndroidManifest.xml"))
        base = "base/manifest/AndroidManifest.xml"
        if base not in manifests:
            raise ValueError("AAB has no base manifest")
        result = None
        for name in [base, *[name for name in manifests if name != base]]:
            apk = Path(temp) / "manifest.apk"
            with zipfile.ZipFile(apk, "w") as destination:
                destination.writestr("AndroidManifest.xml", source.read(name))
                resources = name.split("/", 1)[0] + "/resources.pb"
                if resources in source.namelist():
                    destination.writestr("resources.pb", source.read(resources))
            manifest = parse_manifest(
                run(aapt2, "dump", "badging", str(apk)),
                run(aapt2, "dump", "permissions", str(apk)),
                run(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)),
            )
            if result is None:
                result = manifest
            else:
                for key in ("package_name", "version_code", "version_name"):
                    if manifest[key] != result[key]:
                        raise ValueError(f"{name}: {key} differs from the base manifest")
                result["permissions"].update(manifest["permissions"])
                result["services"].update(manifest["services"])
        if source.namelist().count(REGISTRY_ASSET) > 1:
            raise ValueError("AAB contains duplicate approved-module registries")
        result["packaged_registry_sha256"] = None
        if REGISTRY_ASSET in source.namelist():
            registry = Path(temp) / "approved-module-registry.json"
            registry.write_bytes(source.read(REGISTRY_ASSET))
            result["packaged_registry_sha256"] = SUBMISSION["sha256"](registry)
        return result


def parse_signers(output: str) -> set[str]:
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", output)
    if not fingerprints:
        for signer in re.split(r"Signer #\d+:", output)[1:]:
            # Certificate #1 is the signer; later certificates belong to its issuer chain.
            match = re.search(r"SHA256:\s*([0-9a-fA-F:]+)", signer)
            if match:
                fingerprints.append(match[1])
    result = {fingerprint.replace(":", "").upper() for fingerprint in fingerprints}
    if not result or any(re.fullmatch(r"[0-9A-F]{64}", value) is None for value in result):
        raise ValueError("no valid signer SHA-256 certificate reported")
    return result


def signer_certificates(path: Path, *, apk: bool = False) -> set[str]:
    if not apk:
        try:
            output = run("jarsigner", "-J-Duser.language=en", "-verify", "-strict", str(path))
        except subprocess.CalledProcessError as error:
            if error.returncode != JARSIGNER_CERTIFICATE_ERROR:
                raise
            output = error.output
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        if not lines or lines[0] not in {"jar verified.", "jar verified, with signer errors."}:
            raise ValueError("jarsigner did not verify the AAB")
        unexpected = [line for line in lines[1:]
                      if line not in {"Error:", "Warning:", "Re-run with the -verbose and -certs options for more details."}
                      and not any(re.fullmatch(warning, line) for warning in JARSIGNER_ALLOWED_WARNINGS)]
        if unexpected:
            raise ValueError("jarsigner refused: " + "; ".join(unexpected[:3]))
    try:
        return parse_signers(run("keytool", "-J-Duser.language=en", "-printcert", "-jarfile", str(path)))
    except (ValueError, subprocess.CalledProcessError):
        if not apk:
            raise
        return parse_signers(run(sdk_tool("apksigner"), "verify", "--print-certs", str(path)))


def compare_contract(manifest: dict | None, signers: set[str], properties: dict[str, str]) -> list[str]:
    errors = []
    expected_signer = properties["upload_certificate_sha256"].upper()
    # A release candidate ID names its own version, so a stale ID cannot ride a version bump.
    if f"-v{properties['version_code']}-" not in f"-{properties.get('release_candidate_id', '')}-":
        errors.append(f"release_candidate_id must name v{properties['version_code']}")
    if signers != {expected_signer}:
        errors.append(f"upload_certificate_sha256: expected {expected_signer}, actual {','.join(sorted(signers))}")
    if manifest is None:
        return errors
    for key in ("package_name", "version_code", "version_name"):
        expected = int(properties[key]) if key == "version_code" else properties[key]
        if manifest[key] != expected:
            errors.append(f"{key}: expected {expected}, actual {manifest[key]}")
    maximum = int(properties[MAXIMUM_UPLOADED_VERSION_CODE])
    if manifest["version_code"] <= maximum:
        errors.append(f"version_code {manifest['version_code']} must exceed {MAXIMUM_UPLOADED_VERSION_CODE}={maximum}")
    for field, key in (("permissions", "declared_permissions"), ("services", "declared_services")):
        expected = set(SUBMISSION["csv_values"](properties[key]))
        for value in sorted(expected - manifest[field]):
            errors.append(f"missing {field}: {value}")
        for value in sorted(manifest[field] - expected):
            errors.append(f"extra {field}: {value}")
    return errors


def sbom_errors(aab: Path, digest: str) -> list[str]:
    """The CycloneDX SBOM that bundle<Flavor>Release writes beside the AAB must name these exact bytes."""
    path = aab.with_suffix(".cdx.json")
    try:
        sbom = json.loads(path.read_text(encoding="utf-8"))
        hashes = sbom["metadata"]["component"].get("hashes", [])
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        return [f"SBOM {path.name}: {error!r}"]
    errors = []
    if sbom.get("bomFormat") != "CycloneDX" or not sbom.get("components"):
        errors.append(f"SBOM {path.name} is not a CycloneDX inventory with components")
    if {"alg": "SHA-256", "content": digest} not in hashes:
        errors.append(f"SBOM {path.name} does not name this AAB's SHA-256")
    return errors


def refuse(errors: list[str]) -> None:
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        raise SystemExit(1)


def check(aab: Path, root: Path = ROOT) -> tuple[dict[str, str], str]:
    properties_path = root / "store/play/privacy.properties"
    snapshot = properties_path.read_bytes()
    with tempfile.NamedTemporaryFile(prefix="play-properties-") as stream:
        stream.write(snapshot)
        stream.flush()
        properties = SUBMISSION["read_properties"](Path(stream.name))
    digest = SUBMISSION["sha256"](aab)
    errors = []
    manifest, signers = None, set()
    try:
        manifest = bundle_manifest(aab)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        errors.append(f"AAB manifest: {error}")
    try:
        signers = signer_certificates(aab)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        errors.append(f"AAB signer: {error}")
    errors.extend(compare_contract(manifest, signers, properties))
    errors.extend(sbom_errors(aab, digest))
    if manifest is not None:
        registry_relative, _ = SUBMISSION["registry_contract"](properties)
        approved_hash = properties["approved_module_registry_sha256"]
        if manifest.get("packaged_registry_sha256") != approved_hash or SUBMISSION["sha256"](root / registry_relative) != approved_hash:
            errors.append("packaged registry differs from the approved checked-in registry")
        for key in RELEASE_METADATA.values():
            if manifest["metadata"].get(key) != properties[key]:
                errors.append(f"{key}: artifact metadata differs from approval")
    try:
        SUBMISSION["verify"](root)
    except (SystemExit, OSError, ValueError, KeyError, StopIteration) as error:
        errors.append(f"submission: {error}")
    try:
        properties_unchanged = properties_path.read_bytes() == snapshot
    except OSError:
        properties_unchanged = False
    if not properties_unchanged:
        errors.append("privacy.properties changed during verification")
    if SUBMISSION["sha256"](aab) != digest:
        errors.append("AAB changed during verification")
    refuse(errors)
    print(f"AAB sha256: {digest}")
    return properties, digest


def record_uploaded_version(path: Path, version: int) -> None:
    current = SUBMISSION["read_properties"](path)
    maximum = max(version, int(current[MAXIMUM_UPLOADED_VERSION_CODE]))
    updated, count = re.subn(
        rf"(?m)^[ \t]*{MAXIMUM_UPLOADED_VERSION_CODE}[ \t]*=.*$",
        f"{MAXIMUM_UPLOADED_VERSION_CODE}={maximum}", path.read_text(encoding="utf-8"),
    )
    if count != 1:
        raise ValueError(f"expected exactly one {MAXIMUM_UPLOADED_VERSION_CODE}")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(updated)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.chmod(path.stat().st_mode)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def upload(service_account_json: Path, aab: Path) -> None:
    properties, digest = check(aab)
    # Only upload needs Google's client libraries and credentials, and only the locked ones.
    require_locked_client_libraries()
    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    from googleapiclient.http import MediaFileUpload

    credentials = service_account.Credentials.from_service_account_file(
        str(service_account_json), scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    edits = build("androidpublisher", "v3", credentials=credentials).edits()
    package = properties["package_name"]
    version = int(properties["version_code"])
    edit_id = edits.insert(packageName=package, body={}).execute()["id"]
    committed = False
    try:
        bundle = edits.bundles().upload(
            packageName=package, editId=edit_id,
            media_body=MediaFileUpload(str(aab), mimetype="application/octet-stream"),
        ).execute()
        if bundle.get("sha256") != digest or int(bundle["versionCode"]) != version:
            raise ValueError("Play bundle digest or versionCode differs from the checked AAB; commit refused")
        edits.tracks().update(
            packageName=package, editId=edit_id, track=TRACK,
            body={"track": TRACK, "releases": [{"name": properties["version_name"],
                  "versionCodes": [str(version)], "status": COMPLETED}]},
        ).execute()
        edits.commit(packageName=package, editId=edit_id).execute()
        committed = True
    finally:
        if not committed:
            edits.delete(packageName=package, editId=edit_id).execute()
    read_id = edits.insert(packageName=package, body={}).execute()["id"]
    try:
        track = edits.tracks().get(packageName=package, editId=read_id, track=TRACK).execute()
    finally:
        edits.delete(packageName=package, editId=read_id).execute()
    if not any(release.get("status") == COMPLETED and str(version) in release.get("versionCodes", [])
               for release in track.get("releases", [])):
        raise ValueError(f"committed versionCode {version} is absent from completed releases on {TRACK}")
    record_uploaded_version(ROOT / "store/play/privacy.properties", version)
    print(f"Verified completed {TRACK} release: versionCode={version}, name={properties['version_name']}")


def verify_installed(serial: str) -> None:
    properties = SUBMISSION["read_properties"](ROOT / "store/play/privacy.properties")
    adb = shutil.which("adb")
    if adb is None:
        raise ValueError("adb not found on PATH")
    prefix = (adb, "-s", serial)
    package_info = run(*prefix, "shell", "dumpsys", "package", PACKAGE)
    versions = set(re.findall(r"\bversionCode=(\d+)\b", package_info))
    installers = run(*prefix, "shell", "pm", "list", "packages", "-i", PACKAGE)
    errors = []
    if versions != {properties["version_code"]}:
        errors.append(f"installed version_code: expected {properties['version_code']}, actual {','.join(sorted(versions))}")
    if re.search(rf"^package:{re.escape(PACKAGE)}\s+installer={re.escape(PLAY_INSTALLER)}\s*$",
                 installers, re.MULTILINE) is None:
        errors.append(f"installed installer must be {PLAY_INSTALLER}")
    initiators = set(re.findall(r"\binitiatingPackageName=([^\s]+)", package_info))
    if initiators != {PLAY_INSTALLER}:
        errors.append(f"installed initiatingPackageName must be {PLAY_INSTALLER}, actual {','.join(sorted(initiators)) or 'unknown'}")
    paths = run(*prefix, "shell", "pm", "path", PACKAGE).splitlines()
    bases = [line.removeprefix("package:") for line in paths if line.startswith("package:") and line.endswith("/base.apk")]
    if len(bases) != 1:
        errors.append("installed package must have exactly one base.apk")
    else:
        try:
            with tempfile.TemporaryDirectory(prefix="play-installed-") as temp:
                apk = Path(temp) / "base.apk"
                run(*prefix, "pull", bases[0], str(apk))
                signers = signer_certificates(apk, apk=True)
            expected = properties["play_app_signing_certificate_sha256"].upper()
            if signers != {expected}:
                errors.append(f"play_app_signing_certificate_sha256: expected {expected}, actual {','.join(sorted(signers))}")
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            errors.append(f"installed signer: {error}")
    refuse(errors)
    print(f"Verified Play-installed {PACKAGE} versionCode={properties['version_code']} on {serial}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check").add_argument("aab", type=Path)
    uploading = commands.add_parser("upload")
    uploading.add_argument("service_account_json", type=Path)
    uploading.add_argument("aab", type=Path)
    commands.add_parser("verify-installed").add_argument("--serial", required=True)
    args = parser.parse_args()
    try:
        if args.command == "check":
            check(args.aab)
        elif args.command == "upload":
            upload(args.service_account_json, args.aab)
        else:
            verify_installed(args.serial)
    except Exception as error:
        refuse([str(error)])


if __name__ == "__main__":
    main()
