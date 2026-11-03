#!/usr/bin/env python3
"""Verify that Play submission copy is bound to the selected artifact contract."""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import ipaddress
import json
from pathlib import Path
from urllib.parse import urlsplit


PENDING = "pending_owner_approval"
APPROVED = "approved"
NOT_REQUIRED_INTERNAL = "not_required_internal_testing"
RELEASE_STAGES = {"internal_testing", "play_review"}
APPROVAL_STATUSES = (
    "submission_status",
    "play_account_status",
    "package_ownership_status",
    "signing_lineage_status",
    "version_status",
    "store_category_status",
    "target_ages_status",
    "data_safety_status",
    "legal_copy_status",
    "retention_wording_status",
    "research_app_classification_status",
    "support_identity_status",
)
SOURCE_HASHES = {
    "approved_module_registry_sha256": "app/src/open/assets/approved-module-registry.json",
    "listing_sha256": "store/play/listing.md",
    "data_safety_sha256": "store/play/data-safety.md",
    "health_apps_declaration_sha256": "store/play/health-apps-declaration.md",
    "accessibility_declaration_sha256": "store/play/accessibility-declaration.md",
    "foreground_service_declaration_sha256": "store/play/foreground-service-declaration.md",
    "reviewer_instructions_sha256": "store/play/reviewer-instructions.md",
    "play_console_inventory_sha256": "store/play/play-console-inventory-2026-08-26.md",
}
DEFERRED_UPLOAD_DISCLOSURE = "They are uploaded after connectivity recovers and kept on the device until the enrollment is erased or Chronicle is uninstalled."
TARGET_AGE_GROUPS = {
    "5_and_under",
    "6_8",
    "9_12",
    "13_15",
    "16_17",
    "18_and_over",
}
DATA_SAFETY_QUESTION_COLUMN = "Question ID (machine readable)"
DATA_SAFETY_RESPONSE_COLUMN = "Response value"
DATA_SAFETY_DELETION_QUESTION = "PSL_DATA_COLLECTION_USER_REQUEST_DELETE"


def verify_console_deletion(play_dir: Path, declared: str, data_safety_status: str) -> None:
    exported = play_dir / "data-safety-console.csv"
    if not exported.is_file():
        require(data_safety_status != APPROVED,
                "data_safety_status=approved requires store/play/data-safety-console.csv exported from Play Console")
        return
    with exported.open(encoding="utf-8-sig", newline="") as source:
        responses = [row.get(DATA_SAFETY_RESPONSE_COLUMN, "").strip().lower()
                     for row in csv.DictReader(source) if row.get(DATA_SAFETY_QUESTION_COLUMN) == DATA_SAFETY_DELETION_QUESTION]
    require(len(responses) == 1 and responses[0] in {"true", "false"},
            "Play Console deletion response must appear exactly once as TRUE or FALSE")
    require(responses[0] == declared, "Play Console deletion answer differs from the shipped UI declaration")


def registry_contract(properties: dict[str, str]) -> tuple[str, str]:
    variant = properties.get("play_variant")
    require(variant == "openRelease", "play_variant must be openRelease")
    relative = properties.get("module_registry_path", "")
    require(bool(relative) and not Path(relative).is_absolute() and ".." not in Path(relative).parts,
            "module_registry_path must be relative to the Android root")
    return relative, "OPEN"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"ERROR: {message}")


def read_properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        require("=" in line, f"{path}:{line_number} is not key=value")
        key, value = line.split("=", 1)
        key = key.strip()
        require(bool(key), f"{path}:{line_number} has an empty key")
        require(key not in result, f"{path}:{line_number} duplicates {key}")
        result[key] = value.strip()
    return result


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def csv_values(value: str) -> list[str]:
    values = [item.strip() for item in value.split(",") if item.strip()]
    require(len(values) == len(set(values)), f"CSV contains duplicates: {value}")
    return values


def normalized_text(path: Path) -> str:
    return " ".join(path.read_text(encoding="utf-8").split())


def field_after_heading(lines: list[str], heading: str) -> str:
    require(heading in lines, f"listing is missing {heading}")
    index = lines.index(heading) + 1
    while index < len(lines) and not lines[index].strip():
        index += 1
    require(index < len(lines), f"listing has no value after {heading}")
    return lines[index].strip()


def parse_https_url(value: str, name: str, *, root_only: bool = False):
    parsed = urlsplit(value)
    require(parsed.scheme == "https", f"{name} must use HTTPS")
    require(bool(parsed.hostname), f"{name} must include a hostname")
    require(parsed.username is None and parsed.password is None, f"{name} must not contain credentials")
    require(not parsed.query and not parsed.fragment, f"{name} must not contain query or fragment")
    if root_only:
        require(parsed.path in ("", "/"), f"{name} must be an exact root origin")
    return parsed


def _verify_policy_identity(properties: dict[str, str]) -> None:
    snapshot = dt.date.fromisoformat(properties["policy_snapshot"])
    today = dt.datetime.now(dt.timezone.utc).date()
    require(snapshot <= today, "policy snapshot is in the future")
    require((today - snapshot).days <= 180, "policy snapshot is more than 180 days old")
    require(properties["channel"] == "play", "submission policy channel must be play")
    require(properties["release_stage"] in RELEASE_STAGES, "release_stage is invalid")
    require(properties["package_name"] == "com.bcm.chronicle", "Play package identity drifted")
    require(properties["store_category"] == "parenting", "Play store category drifted")
    require(int(properties["version_code"]) > 0, "version_code must be positive")
    require(bool(properties["version_name"]), "version_name is required")

def _verify_registry_sources(root: Path, properties: dict[str, str]) -> None:
    registry_relative, distribution = registry_contract(properties)
    source_hashes = {**SOURCE_HASHES, "approved_module_registry_sha256": registry_relative}
    for key, relative_path in source_hashes.items():
        source = root / relative_path
        require(source.is_file(), f"submission source is missing: {relative_path}")
        require(
            properties.get(key) == sha256(source),
            f"{key} does not match {relative_path}",
        )

    registry_path = root / registry_relative
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    require(registry.get("schemaVersion") == 1, "approved-module registry schema is unsupported")
    require(registry.get("distribution") == distribution,
            f"approved-module registry distribution must be {distribution} for {properties['play_variant']}")
    modules = registry.get("modules")
    require(isinstance(modules, list) and modules, "approved-module registry has no modules")
    module_ids = [module.get("id") for module in modules]
    require(all(isinstance(value, str) and value for value in module_ids), "registry has an invalid module id")
    require(module_ids == csv_values(properties["approved_module_ids"]), "approved module list drifted")

    uploaded = [module["id"] for module in modules if module["upload"].get("family") != "none"]
    local_only = [module["id"] for module in modules if module["upload"].get("family") == "none"]
    require(uploaded == csv_values(properties["uploaded_module_ids"]), "uploaded module list drifted")
    require(local_only == csv_values(properties["local_only_module_ids"]), "local-only module list drifted")
    expected_local_only = ["questionnaire"] if distribution == "OPEN" else []
    require(local_only == expected_local_only,
            "every approved Play module must have an explicit enrolled-server upload contract (except open questionnaire reminders)")
    upload_telemetry = next(module for module in modules if module["id"] == "upload_telemetry")
    require(
        upload_telemetry["destinations"] == ["local_app_diagnostics_view", "exact_enrolled_study_server"],
        "upload diagnostics destinations drifted",
    )

    base_permissions = set(csv_values(properties["base_permissions"]))
    registry_permissions = {
        permission
        for module in modules
        for permission in module.get("permissions", [])
    }
    declared_permissions = set(csv_values(properties["declared_permissions"]))
    require(
        declared_permissions == base_permissions | registry_permissions,
        "declared permissions do not equal base plus approved-module permissions",
    )

def _verify_listing_disclosures(play_dir: Path, properties: dict[str, str]) -> str:
    listing_path = play_dir / "listing.md"
    listing_lines = listing_path.read_text(encoding="utf-8").splitlines()
    app_name = field_after_heading(listing_lines, "App name (30 characters maximum):")
    short_description = field_after_heading(
        listing_lines,
        "Short description (80 characters maximum):",
    )
    require(len(app_name) <= 30, "Play app name exceeds 30 characters")
    require(len(short_description) <= 80, "Play short description exceeds 80 characters")
    full_description = "\n".join(listing_lines[listing_lines.index("Full description:") + 1:]).strip()
    require(len(full_description) <= 4000, "Play full description exceeds 4000 characters")

    listing = normalized_text(listing_path)
    data_safety_path = play_dir / "data-safety.md"
    data_safety = normalized_text(data_safety_path)
    require(
        DEFERRED_UPLOAD_DISCLOSURE in listing,
        "listing does not disclose deferred upload-diagnostic delivery and retention",
    )
    require(
        "That record is later transmitted to the exact enrolled study server under the active participant/device enrollment"
        in data_safety,
        "Data Safety copy does not classify upload diagnostics as collected",
    )

    parse_https_url(properties["privacy_url"], "privacy_url")
    require(properties["privacy_url"] in listing, "listing privacy URL differs from policy")
    require(properties["support_contact"] in listing, "listing support contact differs from policy")
    return data_safety


def _verify_reviewer_environment(play_dir: Path, properties: dict[str, str]) -> None:
    if properties["release_stage"] == "play_review":
        require(
            properties["reviewer_environment_status"] == APPROVED,
            "Play review requires reviewer_environment_status=approved",
        )
        require(bool(properties["reviewer_owner"]), "Play review requires reviewer_owner")
        reviewer_origin = parse_https_url(properties["reviewer_origin"], "reviewer_origin", root_only=True)
        try:
            ipaddress.ip_address(reviewer_origin.hostname or "")
        except ValueError:
            pass
        else:
            raise SystemExit("ERROR: reviewer_origin must use a publicly trusted hostname, not an IP literal")
        reviewer_copy = normalized_text(play_dir / "reviewer-instructions.md")
        require(
            "Before Play review, replace this paragraph" not in reviewer_copy,
            "reviewer instructions still contain the replacement marker",
        )
    else:
        require(properties["reviewer_environment_status"] in {NOT_REQUIRED_INTERNAL, APPROVED, PENDING},
                "reviewer_environment_status is invalid")



def verify(root: Path) -> None:
    play_dir = root / "store/play"
    properties = read_properties(play_dir / "privacy.properties")
    for status in APPROVAL_STATUSES:
        require(properties.get(status) in {APPROVED, PENDING}, f"{status} must be {APPROVED} or {PENDING}")
    _verify_policy_identity(properties)
    _verify_reviewer_environment(play_dir, properties)
    _verify_registry_sources(root, properties)
    _verify_listing_disclosures(play_dir, properties)
    # WithdrawalDataSafetyContractTest checks this declaration against the compiled study-server UI.
    deletion = properties["deletion_request_supported"]
    require(deletion in {"true", "false"}, "deletion_request_supported must be true or false")
    verify_console_deletion(play_dir, deletion, properties["data_safety_status"])
    require(
        properties["health_apps_classification"] == "human_subjects_research",
        "health classification drifted",
    )



def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    verify(args.root.resolve())
    print(f"Play submission contract passed: {args.root / 'store/play/privacy.properties'}")


if __name__ == "__main__":
    main()
