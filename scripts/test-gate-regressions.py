#!/usr/bin/env python3
"""Gate coverage and exit-status regressions using offline command fixtures."""
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FAILED_JOB_STATUS = 42


STUB_JOBS = '''while read -r name; do
  [[ "$name" == job_group_members ]] || eval "$name() { echo $name; }"
done < <(compgen -A function job_)
'''

class GateRegressions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        result = subprocess.run(['bash', '-s', '--', str(ROOT / 'scripts/local-ci.sh')],
                                input='source "$1"; printf "%s" "$PMD_VERSION"',
                                capture_output=True, text=True, check=True)
        cls.pmd_version = result.stdout

    def run_ci(self, fixture, *jobs, root=ROOT, keep_going='0'):
        shell = ('source "$GATE_SCRIPT"\nROOT_DIR="$FIXTURE_ROOT"; WEB_DIR="$ROOT_DIR/chronicle-web"\n'
                 'require_jdk21() { :; }; require_cmd() { :; }; ensure_ast_grep() { :; }\n'
                 'job_detekt() { printf "detekt:fixture\\n"; }\n'
                 + fixture + '\n' + ('local_ci_main "$@"' if jobs else ''))
        return subprocess.run(
            ['bash', '-s', '--', *jobs], input=shell, capture_output=True, text=True,
            timeout=20, env=dict(os.environ, GATE_SCRIPT=str(ROOT / 'scripts/local-ci.sh'),
                                 FIXTURE_ROOT=str(root), CHRONICLE_LOCAL_CI_REPORT_DIR='',
                                 CHRONICLE_LOCAL_CI_KEEP_GOING=keep_going,
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
        fixture = STUB_JOBS
        fixture += '\njob_web() { false; echo masked-web; }\n'
        fixture += 'job_selfhost() { false; echo masked-selfhost; }\n'
        result = self.run_ci(fixture, 'all', keep_going='1')
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn('masked-', result.stdout)
        for sibling in ('job_jvm_smoke', 'job_license_compliance', 'job_syft',
                        'job_fluent_bit_config', 'job_parity', 'job_android_unit'):
            self.assertIn(sibling, result.stdout, result.stderr)
        self.assertIn('job failed: web', result.stderr)
        self.assertIn('job failed: selfhost', result.stderr)

    def test_web_gate_executes_all_modern_tests(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / 'chronicle-web/node_modules/.bin/bun'
            binary.parent.mkdir(parents=True)
            binary.write_text('#!/bin/bash\nprintf "bun:%s\\n" "$*"\n')
            binary.chmod(0o755)
            security = root / 'tests/security/run-all-security.sh'
            security.parent.mkdir(parents=True)
            security.write_text('exit 0\n')
            result = self.run_ci('bun() { printf "bun:%s\\n" "$*"; }; job_web', root=root)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        filters = []
        for line in result.stdout.splitlines():
            if line.startswith('bun:test '):
                filters.extend(arg for arg in line.split()[1:] if arg.startswith('src/'))
        omitted = ('src/modern/app', 'src/modern/i18n', 'src/modern/styles')
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
                    self.assertTrue(roots, result.stdout)
                    # Both directions: every main Java source of a backend module is scanned, and every scanned
                    # root holds Java (PMD exits 1 on a missing root). A module that gains or loses its Java
                    # sources fails here until job_pmd follows it.
                    for project in ('chronicle-server', 'chronicle-api'):
                        java = list((ROOT / project / 'src/main').rglob('*.java'))
                        self.assertTrue(all(any(path.is_relative_to(root) for root in roots)
                                            for path in java), (project, result.stdout))
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
            uv = root / 'bin/uv'
            uv.parent.mkdir()
            uv.write_text('#!/bin/bash\nprintf "uv:%s\\n" "$*"\n')
            uv.chmod(0o755)
            shell_tests = ('test-verify-play-submission.sh',)
            for name in shell_tests:
                (scripts / name).write_text(f'echo self-test:{name}\nexit "$VERIFIER_STATUS"\n')
            for status in ('0', str(FAILED_JOB_STATUS)):
                fixture = (f'export VERIFIER_STATUS={status}\n'
                           f'export PATH="{uv.parent}:$PATH"\n'
                           'python3() { printf "python3:%s\\n" "$*"; }; job_android_unit')
                result = self.run_ci(fixture, root=root)
                self.assertEqual(status == '0', result.returncode == 0, result.stdout + result.stderr)
                for flavor in ('Open', 'Research', 'Amazon'):
                    self.assertIn(f':app:test{flavor}DebugUnitTest', result.stdout)
                self.assertNotIn(':app:testPlayDebugUnitTest', result.stdout)
                self.assertIn('testDebugUnitTest', result.stdout)
                self.assertIn('uv:run --no-project --python 3.12 --with-requirements '
                              f'{scripts}/play-upload.requirements.txt python {scripts}/test-play-upload.py', result.stdout)
                for name in shell_tests + ('test-release-audit.py',):
                    self.assertIn(name, result.stdout)
                for name in sorted(set(re.findall(r'test-[\w-]+\.(?:py|sh)', result.stdout))):
                    self.assertTrue((ROOT / 'chronicle/scripts' / name).is_file(), name)
            self.assertTrue((root / 'chronicle/app/build/release-audit-tests/tmp').is_dir())

    def test_deploy_gate_runs_full_selfhost_inventory(self):
        fixture = '''source "$1"
security_runner_init() { :; }
security_runner_finalize() { SECURITY_RUNNER_EXIT_CODE=0; }
run_security_step() {
  printf 'step:%s command:' "$1"
  shift 6
  printf ' %s' "$@"
  printf '\n'
}
security_runner_main deploy "$2"
'''
        result = subprocess.run(['bash', '-s', '--', str(ROOT / 'tests/security/run-all-security.sh'), tempfile.gettempdir()],
                                input=fixture, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f'run_selfhost_python_suite discover -s {ROOT}/tests/selfhost-py', result.stdout)
        suites = ('selfhost-cert-init', 'selfhost-backup-selfcheck',
                  'selfhost-deployment-audit', 'selfhost-release-source', 'gitleaks-history-regression')
        security = ROOT / 'tests/security'
        required = {security / f'{suite}.sh' for suite in suites}
        required.update(security.glob('*regressions*.sh'))
        required.update(security.glob('*-W[0-9]*.sh'))
        for script in required:
            self.assertIn(f'bash {script}', result.stdout)
        with tempfile.TemporaryDirectory() as temporary:
            # The wrappers name their scripts by physical path; macOS's temp dir sits behind /var -> /private/var.
            root = Path(temporary).resolve()
            target = root / 'tests/security'
            target.mkdir(parents=True)
            binary = root / 'python3'
            binary.write_text('#!/bin/bash\nprintf "python-arg:%s\\n" "$@"\n')
            binary.chmod(0o755)
            invoked = set()
            for wrapper in security.glob('*regressions*.sh'):
                shipped = target / wrapper.name
                shutil.copy2(wrapper, shipped)
                result = subprocess.run(['bash', str(shipped)], capture_output=True, text=True,
                                        env=dict(os.environ, PATH=temporary + os.pathsep + os.environ['PATH']), timeout=10)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                arguments = (line.removeprefix('python-arg:') for line in result.stdout.splitlines())
                invoked.update(argument for argument in arguments if not argument.startswith('-'))
            self.assertEqual({str(target / script.name) for script in security.glob('*regressions*.py')}, invoked)

    def test_http_smoke_gate_runs_upload_and_preserves_failure(self):
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
                fixture = (f'export UPLOAD_STATUS={status}\n'
                           'run_http_smoke_probes http://fixture.invalid http://frontend.invalid\necho completed')
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
                    result = subprocess.run(['bash', '-s'], input=fixture, env=env,
                                            capture_output=True, text=True, timeout=20)
                    self.assertEqual(not failed, result.returncode == 0, result.stdout + result.stderr)
                    manifest = json.loads((report / 'security-manifest-upload.json').read_text())
                    self.assertEqual(len(suites), len(manifest['steps']))
                    self.assertEqual([FAILED_JOB_STATUS if name == failed else 0 for name in suites],
                                     [step['exitCode'] for step in manifest['steps']])

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
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / 'scripts'
            scripts.mkdir()
            tools = root / 'bin'
            tools.mkdir()
            for name in ('protoc', 'gitleaks'):
                tool = tools / name
                tool.write_text('#!/bin/sh\nexit 99\n')
                tool.chmod(0o755)
            gradle = root / 'gradlew'
            gradle.write_text('#!/bin/bash\nprintf "gradle:%s\\n" "$*"\n')
            gradle.chmod(0o755)
            audit = scripts / 'android-exported-component-audit.sh'
            audit.write_text('#!/bin/bash\nprintf "audit:%s\\n" "$*"\nexit "$AUDIT_STATUS"\n')
            store = root / 'synthetic-store.fixture'
            store.touch()
            properties = root / 'synthetic-input.props'
            properties.write_text(f'storeFile={store}\nstorePassword=synthetic\nkeyAlias=fixture\nkeyPassword=synthetic\n')
            gates = {'android-release-candidate-gate.sh': ('open', 'research', 'amazon')}
            for gate, flavors in gates.items():
                shipped = scripts / gate
                shutil.copy2(ROOT / 'chronicle/scripts' / gate, shipped)
                for flavor in flavors:
                    variant = flavor + 'Release'
                    for status in (0, FAILED_JOB_STATUS):
                        with self.subTest(gate=gate, flavor=flavor, status=status):
                            output = root / 'app/build/play-aab-evidence' / f'{gate}-{variant}-{status}'
                            args = ['--output-dir', str(output), '--variant', variant]
                            env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ['PATH'],
                                       CHRONICLE_SIGNING_PROPERTIES=str(properties), AUDIT_STATUS=str(status),
                                       REPO_PREFLIGHT='/usr/bin/true',
                                       PLAY_UPLOAD_CERT_SHA256='', CHRONICLE_RC_ID='',
                                       CHRONICLE_RELEASE_AUTHORITY_SHA='', CHRONICLE_PLAY_AAB_HELPERS_ONLY='0')
                            result = subprocess.run(['bash', str(shipped), *args], cwd=root,
                                                    env=env, capture_output=True, text=True, timeout=10)
                            # A successful audit reaches the deliberately absent artifact; a failed audit stops first.
                            self.assertEqual(status or 1, result.returncode, result.stdout + result.stderr)
                            self.assertIn(f'audit:--variant {variant} {flavor}', result.stdout)
                            self.assertEqual(status == 0, 'missing:' in result.stderr or 'APK not found' in result.stdout + result.stderr)

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
assert 'X-Chronicle-Random-Device-Id: synthetic-device' in args
assert '--connect-timeout' in args and '--max-time' in args
code = json.loads(os.environ['CURL_CODES'])[index]
# The probe reads the rejection reason from the -o body; answer with the contract's code.
body = args[args.index('-o') + 1]
pathlib.Path(body).write_text(json.dumps({'code': os.environ['CURL_REJECTION_CODE']}))
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
                               TEST_PARTICIPANT_ID='fixture-participant', TEST_RANDOM_DEVICE_ID='synthetic-device',
                               CURL_COUNTER=str(counter), CURL_CODES=json.dumps(codes),
                               CURL_REJECTION_CODE=json.loads((ROOT / 'chronicle-server/src/test/resources/'
                                   'upload-probe-contract.json').read_text())['constraintErrorCode'])
                    result = subprocess.run(['bash', str(ROOT / 'tests/security/file-upload-tests.sh')],
                                            env=env, capture_output=True, text=True, timeout=10)
                    self.assertEqual(codes == accepted, result.returncode == 0, result.stdout + result.stderr)
                    self.assertEqual(len(codes), int(counter.read_text()))


if __name__ == '__main__':
    unittest.main()
