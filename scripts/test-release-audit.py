import unittest, unittest.mock, pathlib, os, subprocess, datetime, re, tempfile, runpy, json
ROOT=pathlib.Path(__file__).resolve().parent.parent; W=pathlib.Path(os.environ['ANDROID_AUDIT_WORK'])
def run_shell(source, *args, **kwargs):
 with tempfile.NamedTemporaryFile(mode='w',suffix='.sh',dir=W) as script:
  script.write(source);script.flush()
  return subprocess.run(['bash',script.name,*args],capture_output=True,text=True,**kwargs)
class ReleaseAudit(unittest.TestCase):
 def test_A08_gate_variant_and_device_selection_use_bash32_syntax(self):
  s=(ROOT/'scripts/android-release-candidate-gate.sh').read_text()
  self.assertNotIn('${build_type_pascal,}',s);self.assertNotIn('mapfile',s);self.assertNotIn('sort -V',s)
  prefix=s.split('signing_file=')[0]
  # Stop before any secret, build, device, or host action.
  prefix=prefix[:prefix.index('mkdir -p')]
  prefix+='\nprintf "%s %s %s\\n" "$flavor" "$build_type" "$assemble_task"\n'
  env=dict(os.environ,REPO_PREFLIGHT=str(W/'nonexistent'))
  for variant in ['openRelease','openDebug','researchReleaseMinified','amazonDogfood']:
   p=run_shell(prefix,'--variant',variant,'--output-dir',str(W/'test-release'),env=env)
   self.assertEqual(0,p.returncode,p.stderr);self.assertIn(':app:assemble'+variant[0].upper()+variant[1:],p.stdout)
  retired=run_shell(prefix,'--variant','playRelease','--output-dir',str(W/'test-release'),env=env)
  self.assertNotEqual(0,retired.returncode,'the retired play flavor must be rejected')
  start=s.index('    devices=')
  end=s.index('    serial="${devices[0]}"',start)+len('    serial="${devices[0]}"')
  block=s[start:end]
  for count in [0,1,2]:
   stub='adb() { printf "List of devices attached\\n'+''.join(f'stub-{i} device\\n' for i in range(count))+'"; };\n'
   p=run_shell('set -euo pipefail\n'+stub+block+'\nprintf "%s" "$serial"')
   self.assertEqual(0 if count==1 else 2,p.returncode,p.stderr)
   if count==1:self.assertEqual('stub-0',p.stdout)
 def test_gate_uses_built_application_id_and_preserves_explicit_override(self):
  s=(ROOT/'scripts/android-release-candidate-gate.sh').read_text()
  start=s.index('if [[ -z "$package_name" ]]')
  end=s.index('echo "package=$package_name" | tee -a "$context_file"',start)
  block=s[start:end]
  with tempfile.TemporaryDirectory(dir=W) as d:
   d=pathlib.Path(d)
   for application_id in ['com.bcm.chronicle','com.bcm.chronicle.drill','com.bcm.chronicle.debug']:
    (d/'output-metadata.json').write_text(json.dumps({'applicationId':application_id}))
    for override in ['', 'com.example.override']:
     p=run_shell('set -euo pipefail\napk_path=$1;package_name=$2\n'+block+'\nprintf "%s" "$package_name"',str(d/'app.apk'),override)
     self.assertEqual(0,p.returncode,p.stderr)
     self.assertEqual(override or application_id,p.stdout)
 def test_A20_current_sdk_is_accepted_and_stale_target_flavor_are_rejected(self):
  with tempfile.TemporaryDirectory(dir=W/'tmp') as d:
   d=pathlib.Path(d); policy=d/'privacy.properties';manifest=d/'manifest.xml'
   policy.write_text('channel=amazon\npolicy_snapshot='+str(datetime.date.today())+'\ndeclared_permissions=android.permission.INTERNET\ndeletion_request_supported=true\nsold=false\nadvertising=false\n')
   javac=subprocess.run(['javac','-d',str(d),str(ROOT/'scripts/StoreReadinessVerifier.java')],capture_output=True,text=True);self.assertEqual(0,javac.returncode,javac.stderr)
   for minimum,target,channel,ok in [(26,36,'amazon',True),(23,36,'amazon',False),(26,35,'amazon',False),(26,36,'play',False)]:
    manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.bcm.chronicle"><uses-sdk android:minSdkVersion="{minimum}" android:targetSdkVersion="{target}"/><uses-permission android:name="android.permission.INTERNET"/><application/></manifest>')
    p=subprocess.run(['java','-cp',str(d),'StoreReadinessVerifier',channel,str(manifest),str(policy)],capture_output=True,text=True)
    self.assertEqual(ok,p.returncode==0,p.stderr)
 def test_A38_tree_is_recorded_upload_or_next_candidate(self):
  s=(ROOT/'app/build.gradle').read_text();code=int(re.search(r'versionCode (\d+)',s)[1])
  policy=dict(l.split('=',1) for l in (ROOT/'store/play/privacy.properties').read_text().splitlines() if '=' in l)
  self.assertEqual(code,int(policy['version_code']));self.assertEqual(re.search(r'versionName "([^"]+)"',s)[1],policy['version_name'])
  # The tree is the recorded upload or its next candidate; upload checks still require a new code.
  self.assertIn(code-int(policy['maximum_uploaded_version_code']),(0,1))
 def test_A38_accepts_recorded_upload_or_next_candidate_only(self):
  maximum_key=runpy.run_path(str(pathlib.Path(__file__).with_name('play-upload.py')))['MAXIMUM_UPLOADED_VERSION_CODE']
  with tempfile.TemporaryDirectory(dir=W/'tmp') as d:
   root=pathlib.Path(d); (root/'app').mkdir(); (root/'store/play').mkdir(parents=True)
   (root/'app/build.gradle').write_text('versionCode 70\nversionName "release.70"\n')
   for maximum,valid in [(70,True),(69,True),(71,False),(68,False)]:
    with self.subTest(maximum=maximum):
     (root/'store/play/privacy.properties').write_text(
      f"version_code=70\nversion_name=release.70\n{maximum_key}={maximum}\n")
     with unittest.mock.patch.dict(globals(),ROOT=root):
      if valid:
       self.test_A38_tree_is_recorded_upload_or_next_candidate()
      else:
       with self.assertRaises(AssertionError):
        self.test_A38_tree_is_recorded_upload_or_next_candidate()
if __name__=='__main__':unittest.main()
