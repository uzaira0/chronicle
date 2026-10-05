import unittest, pathlib, os, subprocess, datetime, re, tempfile
ROOT=pathlib.Path(__file__).resolve().parent.parent; W=pathlib.Path(os.environ['ANDROID_AUDIT_WORK'])
class ReleaseAudit(unittest.TestCase):
 def test_A33_candidate_gate_stores_a_variant_inventory_bound_to_sealed_artifact(self):
  import json, hashlib
  gate=(ROOT/'scripts/android-release-candidate-gate.sh').read_text()
  self.assertIn(':app:generateRuntimeSbom "-PsbomVariant=$variant"',gate)
  self.assertIn('bind-candidate-sbom.py',gate)
  self.assertIn('digest(artifact.bytes)',(ROOT/'app/build.gradle').read_text())
  for extension in ['apk','aab']:
   with tempfile.TemporaryDirectory(dir=W/'tmp') as d:
    d=pathlib.Path(d);apk=d/('candidate.'+extension);apk.write_bytes(b'synthetic sealed APK bytes')
    inventory=d/'inventory.json';inventory.write_text(json.dumps({'bomFormat':'CycloneDX','specVersion':'1.5','metadata':{'component':{'name':'chronicle-android','version':'2026.10.03-rc.1'}},'components':[{'type':'library','name':'synthetic','version':'1'}]}))
    output=d/'evidence'
    result=subprocess.run(['python3',str(ROOT/'scripts/bind-candidate-sbom.py'),str(inventory),str(apk),str(output),'playRelease','68','2026.10.03-rc.1'],capture_output=True,text=True)
    self.assertEqual(0,result.returncode,result.stderr)
    candidate=json.loads((output/'candidate.json').read_text());sbom=output/'candidate.cdx.json'
    self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(),candidate['artifactSha256'])
    self.assertEqual(hashlib.sha256(sbom.read_bytes()).hexdigest(),candidate['sbomSha256'])
    self.assertEqual(candidate['artifactSha256'],json.loads(sbom.read_text())['metadata']['component']['hashes'][0]['content'])
    self.assertEqual('playRelease',candidate['variant']);self.assertEqual(68,candidate['versionCode'])
    self.assertTrue(candidate['ownerApprovalRequired']);self.assertIn(candidate['artifactSha256'],candidate['approvalSubject'])
if __name__=='__main__':unittest.main()
