import unittest, pathlib, os, subprocess, datetime, re, tempfile
ROOT=pathlib.Path(__file__).resolve().parent.parent; W=pathlib.Path(os.environ['ANDROID_AUDIT_WORK'])
class ReleaseAudit(unittest.TestCase):
 def test_A08_gate_variant_and_device_selection_use_bash32_syntax(self):
  s=(ROOT/'scripts/android-release-candidate-gate.sh').read_text()
  self.assertNotIn('${build_type_pascal,}',s);self.assertNotIn('mapfile',s);self.assertNotIn('sort -V',s)
  prefix=s.split('signing_file=')[0]
  # Stop before any secret, build, device, or host action.
  prefix=prefix[:prefix.index('mkdir -p')]
  prefix+='\nprintf "%s %s %s\\n" "$flavor" "$build_type" "$assemble_task"\n'
  env=dict(os.environ,REPO_PREFLIGHT=str(W/'nonexistent'))
  for variant in ['playRelease','openDebug','researchReleaseMinified','amazonDogfood']:
   p=subprocess.run(['bash','-c',prefix,'gate','--variant',variant,'--output-dir',str(W/'test-release')],env=env,capture_output=True,text=True)
   self.assertEqual(0,p.returncode,p.stderr);self.assertIn(':app:assemble'+variant[0].upper()+variant[1:],p.stdout)
  start=s.index('    devices=')
  end=s.index('    serial="${devices[0]}"',start)+len('    serial="${devices[0]}"')
  block=s[start:end]
  for count in [0,1,2]:
   stub='adb() { printf "List of devices attached\\n'+''.join(f'stub-{i} device\\n' for i in range(count))+'"; };\n'
   p=subprocess.run(['bash','-c','set -euo pipefail\n'+stub+block+'\nprintf "%s" "$serial"'],capture_output=True,text=True)
   self.assertEqual(0 if count==1 else 2,p.returncode,p.stderr)
   if count==1:self.assertEqual('stub-0',p.stdout)
 def test_A20_current_sdk_is_accepted_and_stale_target_flavor_are_rejected(self):
  with tempfile.TemporaryDirectory(dir=W/'tmp') as d:
   d=pathlib.Path(d); policy=d/'privacy.properties';manifest=d/'manifest.xml'
   policy.write_text('channel=amazon\npolicy_snapshot='+str(datetime.date.today())+'\ndeclared_permissions=android.permission.INTERNET\ndeletion_request_supported=true\nsold=false\nadvertising=false\n')
   javac=subprocess.run(['javac','-d',str(d),str(ROOT/'scripts/StoreReadinessVerifier.java')],capture_output=True,text=True);self.assertEqual(0,javac.returncode,javac.stderr)
   for minimum,target,channel,ok in [(26,36,'amazon',True),(23,36,'amazon',False),(26,35,'amazon',False),(26,36,'play',False)]:
    manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.bcm.chronicle"><uses-sdk android:minSdkVersion="{minimum}" android:targetSdkVersion="{target}"/><uses-permission android:name="android.permission.INTERNET"/><application/></manifest>')
    p=subprocess.run(['java','-cp',str(d),'StoreReadinessVerifier',channel,str(manifest),str(policy)],capture_output=True,text=True)
    self.assertEqual(ok,p.returncode==0,p.stderr)
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
