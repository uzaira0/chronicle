#!/usr/bin/env python3
"""Gate coverage and exit-status regressions using offline command fixtures."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FAILED_JOB_STATUS = 42


class GateRegressions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = (ROOT / 'scripts/local-ci.sh').read_text()
        boundary = source.index('\nif [[ "$#" -eq 0 ]]')
        cls.pmd_version = re.search(r'^PMD_VERSION="([^"]+)"$', source, re.M).group(1)
        cls.functions = f'PMD_VERSION="{cls.pmd_version}"\n' + source[source.index('usage() {'):boundary]
        cls.main = source[boundary:]

    def run_ci(self, fixture, *jobs, root=ROOT, keep_going='0'):
        shell = ('set -euo pipefail\n' + self.functions + '\n'
                 'require_jdk21() { :; }; require_cmd() { :; }; ensure_ast_grep() { :; }\n'
                 + fixture + '\n' + (self.main if jobs else ''))
        return subprocess.run(
            ['bash', '-c', shell, 'gate-fixture', *jobs], capture_output=True, text=True,
            timeout=20, env=dict(os.environ, ROOT_DIR=str(root), WEB_DIR=str(root / 'chronicle-web'),
                                 LOCAL_REPORT_DIR='', CHRONICLE_LOCAL_CI_KEEP_GOING=keep_going,
                                 FAILED_JOB_STATUS=str(FAILED_JOB_STATUS)),
        )

    def test_keep_going_preserves_early_job_failure(self):
        fixture = '''gradle() {
  printf 'gradle:%s\n' "$*"
  [[ "$1" != :chronicle-server:test ]] || return "$FAILED_JOB_STATUS"
}
job_web() { echo later-job; }
'''
        for keep_going in ('0', '1'):
            with self.subTest(keep_going=keep_going):
                result = self.run_ci(fixture, 'jvm-smoke', 'web', keep_going=keep_going)
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertNotIn('gradle::chronicle-server:jacocoTestReport', result.stdout)
                self.assertEqual(keep_going == '1', 'later-job' in result.stdout)
                if keep_going == '1':
                    self.assertIn(f'job failed: jvm-smoke status={FAILED_JOB_STATUS}', result.stderr)

    def test_keep_going_visits_siblings_in_nested_groups(self):
        names = set(re.findall(r'\) (job_\w+) ;;', self.functions))
        fixture = '\n'.join(f'{name}() {{ echo {name}; }}' for name in sorted(names))
        fixture += '\njob_web() { false; echo masked-web; }\n'
        fixture += 'job_selfhost() { false; echo masked-selfhost; }\n'
        result = self.run_ci(fixture, 'all', keep_going='1')
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn('masked-', result.stdout)
        for sibling in ('job_jvm_smoke', 'job_license_compliance', 'job_syft',
                        'job_fluent_bit_config', 'job_parity', 'job_android_unit'):
            self.assertIn(sibling, result.stdout)
        self.assertIn('job failed: web', result.stderr)
        self.assertIn('job failed: selfhost', result.stderr)

    def test_web_gate_executes_all_modern_and_core_tests(self):
        result = self.run_ci('bun() { printf "bun:%s\\n" "$*"; }; job_web')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        filters = []
        for line in result.stdout.splitlines():
            if line.startswith('bun:test '):
                filters.extend(arg for arg in line.split()[1:] if arg.startswith('src/'))
        omitted = ('src/modern/app', 'src/modern/i18n', 'src/modern/styles', 'src/core')
        for directory in omitted:
            tests = list((ROOT / 'chronicle-web' / directory).rglob('*.test.*'))
            self.assertTrue(tests, directory)
            for test in tests:
                relative = str(test.relative_to(ROOT / 'chronicle-web'))
                self.assertTrue(any(relative.startswith(path.rstrip('/') + '/') for path in filters), relative)

    def test_jvm_gate_runs_shared_project_tests(self):
        result = self.run_ci('gradle() { printf "gradle:%s\\n" "$*"; }; job_jvm_smoke')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        tasks = result.stdout.split()
        for project in ('chronicle-models', 'rhizome', 'rhizome-client'):
            self.assertTrue(any(f':{project}:test' in task for task in tasks), result.stdout)

    def test_jvm_gate_enforces_analysis_and_mutation(self):
        tasks = (':chronicle-server:check', ':rhizome:spotbugsMain', ':chronicle-server:pitest')
        fixture = '''gradle() {
  printf 'gradle:%s\\n' "$*"
  for task in "$@"; do
    [[ "$task" != "$FAILED_TASK" ]] || return "$FAILED_JOB_STATUS"
  done
}
'''
        for failed in ('', *tasks):
            for keep_going in ('0', '1'):
                with self.subTest(failed=failed, keep_going=keep_going):
                    result = self.run_ci(f'FAILED_TASK="{failed}"\n' + fixture, 'jvm-smoke',
                                         keep_going=keep_going)
                    self.assertEqual(not failed, result.returncode == 0, result.stdout + result.stderr)
                    calls = [line.removeprefix('gradle:').split() for line in result.stdout.splitlines()
                             if line.startswith('gradle:')]
                    if not failed:
                        for task in tasks:
                            self.assertTrue(any(task in call for call in calls), result.stdout)
                    elif keep_going == '1':
                        self.assertIn(f'job failed: jvm-smoke status={FAILED_JOB_STATUS}', result.stderr)
                    self.assertFalse(any(arg.startswith('-PpitMutationThreshold') for call in calls for arg in call))

    def test_pmd_scans_java_sources_and_preserves_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            binary = Path(temporary) / f'pmd-{self.pmd_version}/bin/pmd'
            binary.parent.mkdir(parents=True)
            binary.write_text('#!/bin/bash\nprintf "pmd-arg:%s\\n" "$@"\nexit "$PMD_STATUS"\n')
            binary.chmod(0o755)
            for status in (0, FAILED_JOB_STATUS):
                with self.subTest(status=status):
                    fixture = (f'LOCAL_BIN_DIR="{temporary}"\nexport PMD_STATUS={status}\n'
                               'ensure_pmd() { :; }; job_pmd')
                    result = self.run_ci(fixture)
                    self.assertEqual(status, result.returncode, result.stdout + result.stderr)
                    args = [line.removeprefix('pmd-arg:') for line in result.stdout.splitlines()
                            if line.startswith('pmd-arg:')]
                    self.assertIn('rulesets/java/quickstart.xml', args)
                    roots = [Path(args[index + 1]) for index, arg in enumerate(args) if arg == '-d']
                    for project in ('chronicle-server', 'chronicle-api'):
                        java = list((ROOT / project / 'src/main').rglob('*.java'))
                        self.assertTrue(java, project)
                        self.assertTrue(all(any(path.is_relative_to(root) for root in roots)
                                            for path in java), result.stdout)
                    self.assertTrue(all(list(root.rglob('*.java')) for root in roots), result.stdout)

    def test_android_gate_runs_every_distribution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            security = root / 'tests/security/run-all-security.sh'
            security.parent.mkdir(parents=True)
            security.write_text('exit 0\n')
            gradle = root / 'chronicle/gradlew'
            gradle.parent.mkdir()
            gradle.write_text('#!/bin/bash\nprintf "gradle:%s\\n" "$*"\n')
            gradle.chmod(0o755)
            scripts = root / 'chronicle/scripts'
            scripts.mkdir()
            shell_tests = ('test-verify-play-aab-runtime-helpers.sh', 'test-verify-play-submission.sh')
            for name in shell_tests:
                (scripts / name).write_text(f'echo self-test:{name}\nexit "$VERIFIER_STATUS"\n')
            for status in ('0', str(FAILED_JOB_STATUS)):
                fixture = (f'export VERIFIER_STATUS={status}\n'
                           'python3() { printf "python3:%s\\n" "$*"; }; job_android_unit')
                result = self.run_ci(fixture, root=root)
                self.assertEqual(status == '0', result.returncode == 0, result.stdout + result.stderr)
                for flavor in ('Open', 'Research', 'Play', 'Amazon'):
                    self.assertIn(f':app:test{flavor}DebugUnitTest', result.stdout)
                self.assertIn('testDebugUnitTest', result.stdout)
                for name in shell_tests + ('test-release-audit.py', 'test-explicit-signing-input.py'):
                    self.assertIn(name, result.stdout)
            self.assertTrue((root / 'chronicle/app/build/release-audit-tests/tmp').is_dir())

    def test_deploy_gate_runs_full_selfhost_inventory(self):
        source = (ROOT / 'tests/security/run-all-security.sh').read_text()
        case = source[source.index('case "$LAYER" in'):source.index('\necho "Layer $LAYER completed.')]
        fixture = '''set -euo pipefail
run_security_step() {
  printf 'step:%s command:' "$1"
  shift 6
  printf ' %s' "$@"
  printf '\n'
}
''' + case
        result = subprocess.run(['bash', '-c', fixture], capture_output=True, text=True, timeout=10,
                                env=dict(os.environ, ROOT_DIR=str(ROOT), REPORT_DIR='/unused', LAYER='deploy'))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f'python3 -m unittest discover -s {ROOT}/tests/selfhost-py', result.stdout)
        suites = ('selfhost-cert-init', 'selfhost-backup-selfcheck',
                  'selfhost-deployment-audit', 'selfhost-release-source', 'gitleaks-history-regression')
        security = ROOT / 'tests/security'
        required = {security / f'{suite}.sh' for suite in suites}
        required.update(security.glob('*regressions*.sh'))
        required.update(security.glob('*-W[0-9]*.sh'))
        for script in required:
            self.assertIn(f'bash {script}', result.stdout)
        wrappers = '\n'.join(script.read_text() for script in required)
        for script in security.glob('*regressions*.py'):
            # The scheduled wrapper must run the whole suite, without selecting one test.
            self.assertRegex(wrappers, rf'(?m)^python3 "\$ROOT_DIR/tests/security/{re.escape(script.name)}"\s*$')

    def test_http_smoke_gate_runs_upload_and_preserves_failure(self):
        start = self.functions.index('  "$ROOT_DIR/scripts/smoke-test.sh"')
        block = self.functions[start:self.functions.index('\n}', start)]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, body in {'scripts/smoke-test.sh': 'exit 0',
                               'tests/security/run-all-security.sh':
                               'printf "upload-gate:%s:%s\\n" "$BASE_URL" "$1"\nexit "$UPLOAD_STATUS"'}.items():
                script = root / name
                script.parent.mkdir(parents=True, exist_ok=True)
                script.write_text('#!/bin/bash\n' + body + '\n')
                script.chmod(0o755)
            for status in ('0', str(FAILED_JOB_STATUS)):
                fixture = ('smoke_upload() {\n' + block + '\n}\n'
                           'backend_url=http://fixture.invalid; frontend_url=http://frontend.invalid\n'
                           f'export UPLOAD_STATUS={status}\nsmoke_upload\necho completed')
                result = self.run_ci(fixture, root=root)
                self.assertEqual(int(status), result.returncode, result.stdout + result.stderr)
                self.assertIn('upload-gate:http://fixture.invalid:upload', result.stdout)
                self.assertEqual(status == '0', 'completed' in result.stdout)

    def test_live_upload_gate_runs_all_suites_after_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            suites = ('tests/security/file-upload-tests.sh', 'tests/load/smoke-test.sh', 'k6')
            for name in suites:
                script = root / name
                script.parent.mkdir(parents=True, exist_ok=True)
                script.write_text(f'#!/bin/bash\n[[ "$FAILED_SUITE" != {name} ]] || exit "$FAILED_JOB_STATUS"\n')
                script.chmod(0o755)
            runner = ROOT / 'tests/security/run-all-security.sh'
            fixture = (f'source "{runner}"\nROOT_DIR="$FIXTURE_ROOT"\n'
                       'status=0\nsecurity_runner_main upload "$REPORT_DIR" || status=$?\nexit "$status"')
            for failed in ('', *suites):
                with self.subTest(failed=failed):
                    report = root / 'reports'
                    env = dict(os.environ, PATH=temporary + os.pathsep + os.environ['PATH'],
                               FIXTURE_ROOT=temporary, REPORT_DIR=str(report), FAILED_SUITE=failed,
                               FAILED_JOB_STATUS=str(FAILED_JOB_STATUS))
                    result = subprocess.run(['bash', '-c', fixture], env=env,
                                            capture_output=True, text=True, timeout=20)
                    self.assertEqual(not failed, result.returncode == 0, result.stdout + result.stderr)
                    manifest = json.loads((report / 'security-manifest-upload.json').read_text())
                    self.assertEqual(len(suites), len(manifest['steps']))
                    self.assertEqual([FAILED_JOB_STATUS if name == failed else 0 for name in suites],
                                     [step['exitCode'] for step in manifest['steps']])

    def test_openapi_gate_rejects_removed_operation(self):
        binary = shutil.which('oasdiff') or ROOT / '.local/bin/oasdiff'
        self.assertTrue(os.access(binary, os.X_OK), 'oasdiff is required for the compatibility regression')
        spec = {'openapi': '3.0.3', 'info': {'title': 'Gate fixture', 'version': '1.0.0'},
                'paths': {'/upload': {'post': {'responses': {'200': {'description': 'Accepted'}}}}}}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'oasdiff').symlink_to(binary)
            base = root / 'base/chronicle-api/chronicle.yaml'
            head = root / 'chronicle-api/chronicle.yaml'
            for path in (base, head):
                path.parent.mkdir(parents=True)
                path.write_text(json.dumps(spec))
            fixture = ('ensure_oasdiff() { :; }; oasdiff() { "$ROOT_DIR/oasdiff" "$@"; }\n'
                       'unset BASE_SPEC HEAD_SPEC GITHUB_STEP_SUMMARY\njob_openapi_diff\necho compatible')
            result = self.run_ci(fixture, root=root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            spec['paths'] = {}
            head.write_text(json.dumps(spec))
            result = self.run_ci(fixture, root=root)
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertNotIn('compatible', result.stdout)

    def test_fuzz_gate_enables_each_bounded_target_separately(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            tests = root / 'chronicle-server/src/test'
            tests.mkdir(parents=True)
            for name, methods in {'OneFuzzTest': ('first', 'second'), 'TwoFuzzTest': ('third',)}.items():
                annotations = '\n'.join(f'@FuzzTest(maxDuration = "30s")\nfun {method}(data: String) {{}}' for method in methods)
                (tests / f'{name}.kt').write_text(f'package fixture\nclass {name} {{\n{annotations}\n}}')
            fixture = 'gradle() { printf "fuzz:%s:%s\\n" "${JAZZER_FUZZ:-}" "$*"; }; job_fuzz'
            result = self.run_ci(fixture, root=root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            calls = [line for line in result.stdout.splitlines() if line.startswith('fuzz:')]
            self.assertEqual(3, len(calls), result.stdout)
            for target in ('OneFuzzTest.first', 'OneFuzzTest.second', 'TwoFuzzTest.third'):
                self.assertTrue(any(line.startswith('fuzz:1:') and f'--tests fixture.{target} ' in line
                                    and '--rerun-tasks' in line for line in calls), target)
            (tests / 'UnboundedFuzzTest.kt').write_text('package fixture\nclass UnboundedFuzzTest {\n@FuzzTest()\nfun forever(data: String) {}\n}')
            result = self.run_ci(fixture, root=root)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn('fuzz:1:', result.stdout)

    def test_release_gates_require_exported_component_audit(self):
        android = ROOT / 'chronicle'
        candidate = (android / 'scripts/android-release-candidate-gate.sh').read_text()
        play = (android / 'scripts/verify-play-aab.sh').read_text()
        blocks = (
            candidate[candidate.index('build_started_epoch='):candidate.index('\nif [[ ! -f "$apk_path" ]]')],
            play[play.index('build_started_epoch='):play.index('\n[[ -f "$AAB_PATH" ]]')],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, body in {'gradlew': 'exit 0', 'scripts/android-exported-component-audit.sh':
                               'printf "audit:%s\\n" "$*"\nexit "$AUDIT_STATUS"'}.items():
                script = root / name
                script.parent.mkdir(parents=True, exist_ok=True)
                script.write_text('#!/bin/bash\n' + body + '\n')
                script.chmod(0o755)
            for index, block in enumerate(blocks):
                flavors = ('open', 'research', 'play', 'amazon') if index == 0 else ('play',)
                for flavor in flavors:
                    variant = flavor + 'Release'
                    for status in ('0', str(FAILED_JOB_STATUS)):
                        with self.subTest(gate=index, flavor=flavor, status=status):
                            env = dict(os.environ, ROOT_DIR=temporary, output_dir=temporary, OUTPUT_DIR=temporary,
                                       context_file=str(root / 'context.txt'), JAVA_HOME='/fixture/jdk',
                                       variant=variant, flavor=flavor, assemble_task=':app:assemble' + variant[0].upper() + variant[1:],
                                       skip_build='0', SKIP_BUILD='0', AUDIT_STATUS=status)
                            result = subprocess.run(['bash', '-c', 'set -euo pipefail\n' + block + '\necho audited'],
                                                    env=env, capture_output=True, text=True, timeout=10)
                            self.assertEqual(int(status), result.returncode, result.stdout + result.stderr)
                            self.assertIn(f'audit:--variant {variant} {flavor}', result.stdout)
                            self.assertEqual(status == '0', 'audited' in result.stdout)

    def test_upload_probes_fail_wrong_routes_auth_and_transport(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            curl = root / 'curl'
            curl.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
counter = pathlib.Path(os.environ['CURL_COUNTER'])
index = int(counter.read_text()) if counter.exists() else 0
counter.write_text(str(index + 1))
args = sys.argv[1:]
url = next(arg for arg in args if arg.startswith('http'))
assert '/chronicle/v4/study/' in url and url.endswith('/android/sensors'), url
assert 'X-Api-Key: synthetic-fixture-key' in args
assert 'X-Chronicle-Device-Id: synthetic-device' in args
assert '--connect-timeout' in args and '--max-time' in args
code = json.loads(os.environ['CURL_CODES'])[index]
print(code if code != 'transport' else '000', end='')
sys.exit(7 if code == 'transport' else 0)
''')
            curl.chmod(0o755)
            counter = root / 'calls'
            accepted = ['200', '413', '400', '400', '400', '403', '400']
            cases = [accepted] + [[code] for code in ('404', '401', '403', 'transport')]
            for index in range(1, len(accepted)):
                codes = accepted.copy()
                codes[index] = '404'
                cases.append(codes)
            for codes in cases:
                with self.subTest(codes=codes):
                    counter.unlink(missing_ok=True)
                    env = dict(os.environ, PATH=temporary + os.pathsep + os.environ['PATH'],
                               BASE_URL='https://fixture.invalid', API_KEY='synthetic-fixture-key',
                               TEST_STUDY_ID='00000000-0000-0000-0000-000000000002',
                               TEST_PARTICIPANT_ID='fixture-participant', TEST_DEVICE_ID='synthetic-device',
                               CURL_COUNTER=str(counter), CURL_CODES=json.dumps(codes))
                    result = subprocess.run(['bash', str(ROOT / 'tests/security/file-upload-tests.sh')],
                                            env=env, capture_output=True, text=True, timeout=10)
                    self.assertEqual(codes == accepted, result.returncode == 0, result.stdout + result.stderr)
                    self.assertEqual(len(codes), int(counter.read_text()))


if __name__ == '__main__':
    unittest.main()
