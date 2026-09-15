#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/gates/env.sh
rm -rf target/surefire-reports target/failsafe-reports
mvn -q -B verify
python3 - <<'EOF'
import glob
import sys
import xml.etree.ElementTree as ET

tests = skipped = 0
for path in glob.glob("target/surefire-reports/TEST-*.xml") + glob.glob("target/failsafe-reports/TEST-*.xml"):
    root = ET.parse(path).getroot()
    tests += int(root.get("tests"))
    skipped += int(root.get("skipped"))
if tests < 30 or skipped:
    sys.exit(f"mvn verify: tests={tests} skipped={skipped}, нужно не меньше 30 тестов без пропусков")
print(f"MVN VERIFY OK tests={tests} skipped=0")
EOF
