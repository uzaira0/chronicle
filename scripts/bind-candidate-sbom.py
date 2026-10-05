#!/usr/bin/env python3
"""Bind a candidate inventory and owner approval subject to the sealed artifact bytes."""
import hashlib
import json
from pathlib import Path
import sys

def bind(inventory, artifact, output, variant, version_code, version_name):
    document = json.loads(inventory.read_text())
    if document.get("bomFormat") != "CycloneDX" or not document.get("components"):
        raise ValueError("A nonempty CycloneDX runtime inventory is required")
    component = document.setdefault("metadata", {}).setdefault("component", {})
    if component.get("version") != version_name:
        raise ValueError("Inventory version does not match the candidate")
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    component["hashes"] = [{"alg": "SHA-256", "content": digest}]
    document["metadata"]["properties"] = [{"name": "chronicle:variant", "value": variant},
        {"name": "chronicle:versionCode", "value": str(version_code)}]
    output.mkdir(parents=True, exist_ok=True)
    sbom = output / "candidate.cdx.json"
    sbom.write_text(json.dumps(document, indent=2) + "\n")
    candidate = {"variant": variant, "versionCode": int(version_code), "versionName": version_name,
        "artifactSha256": digest, "sbomSha256": hashlib.sha256(sbom.read_bytes()).hexdigest(),
        "ownerApprovalRequired": True,
        "approvalSubject": f"{variant}:{version_code}:{version_name}:sha256:{digest}"}
    (output / "candidate.json").write_text(json.dumps(candidate, indent=2) + "\n")

if __name__ == "__main__":
    if len(sys.argv) != 7:
        raise SystemExit("usage: bind-candidate-sbom.py inventory artifact output variant versionCode versionName")
    bind(*(Path(arg) for arg in sys.argv[1:4]), *sys.argv[4:])
