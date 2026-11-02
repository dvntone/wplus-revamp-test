#!/usr/bin/env bash
# Offline tests for the logic embedded in .github/workflows/*.yml.
# Extracts the inline scripts and runs them with mocked inputs, so the
# workflows can be checked without a runner. Requires bash, python3+PyYAML,
# node and java (for the keystore.properties round-trip).
#
#   ./scripts/workflow-tests/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
HERE=scripts/workflow-tests
export WT_TMP; WT_TMP=$(mktemp -d); trap 'rm -rf "$WT_TMP"' EXIT

python3 - "$WT_TMP" <<'PY'
import yaml, sys, glob, re, subprocess
t = sys.argv[1]
y = lambda f: yaml.safe_load(open(f))
rel = y('.github/workflows/android-release.yml')
open(f'{t}/calc.sh', 'w').write(next(s for s in rel['jobs']['resolve']['steps'] if s.get('id') == 'calc')['run'])
open(f'{t}/notify.js', 'w').write(y('.github/workflows/notify-waiting.yml')['jobs']['notify']['steps'][0]['with']['script'])
cq = y('.github/workflows/code-quality.yml')
open(f'{t}/summary.js', 'w').write(next(s for s in cq['jobs']['post-summary']['steps'] if 'github-script' in s.get('uses', ''))['with']['script'])
# Syntax: every github-script block parses as JS, every ubuntu run: step passes bash -n.
for f in sorted(glob.glob('.github/workflows/*.yml')):
    for jn, j in y(f)['jobs'].items():
        for s in j.get('steps', []):
            if 'github-script' in str(s.get('uses', '')):
                r = subprocess.run(['node', '-e', 'const A=Object.getPrototypeOf(async function(){}).constructor;new A(require("fs").readFileSync(0,"utf8"))'], input=s['with']['script'], text=True, capture_output=True)
                assert r.returncode == 0, (f, jn, r.stderr)
            if 'run' in s and str(j.get('runs-on', '')).startswith('ubuntu'):
                r = subprocess.run(['bash', '-n'], input=re.sub(r'\$\{\{.*?\}\}', 'X', s['run']), text=True, capture_output=True)
                assert r.returncode == 0, (f, jn, s.get('name'), r.stderr)
    print('ok   syntax', f)
PY

echo "== pipefail (code-quality uses shell: bash)"
printf 'false | tee /dev/null\n' > "$WT_TMP/step.sh"
if bash --noprofile --norc -eo pipefail "$WT_TMP/step.sh" 2>/dev/null; then echo "FAIL pipefail"; exit 1; else echo "ok   piped failure propagates"; fi

echo "== keystore.properties round-trip through java.util.Properties"
( cd "$WT_TMP"
  export KS_STORE_PASSWORD='ab\cd$x`y\u00zz' KS_KEY_ALIAS='al ias' KS_KEY_PASSWORD='p\\q'
  {
    echo "storeFile=wscanplus-release.jks"
    printf 'storePassword=%s\n' "${KS_STORE_PASSWORD//\\/\\\\}"
    printf 'keyAlias=%s\n' "${KS_KEY_ALIAS//\\/\\\\}"
    printf 'keyPassword=%s\n' "${KS_KEY_PASSWORD//\\/\\\\}"
  } > keystore.properties
  cat > T.java <<'J'
import java.util.*;import java.io.*;
public class T{public static void main(String[] a)throws Exception{Properties p=new Properties();p.load(new FileInputStream("keystore.properties"));
 for(String[] k:new String[][]{{"storePassword","KS_STORE_PASSWORD"},{"keyAlias","KS_KEY_ALIAS"},{"keyPassword","KS_KEY_PASSWORD"}})
  if(!p.getProperty(k[0]).equals(System.getenv(k[1]))){System.out.println("FAIL "+k[0]);System.exit(1);}
 System.out.println("ok   special-character secrets survive");}}
J
  java T.java 2>&1 | grep -v JAVA_TOOL_OPTIONS )
grep -q 'KS_STORE_PASSWORD//' .github/workflows/android.yml || { echo "FAIL workflow no longer escapes backslashes"; exit 1; }

echo "== release version / versionCode"
bash "$HERE/test_version_calc.sh"

echo "== github-script logic (mocked API)"
node "$HERE/test_github_scripts.js" "$WT_TMP"
