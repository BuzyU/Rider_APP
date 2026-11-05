#!/usr/bin/env bash
# Fails if DEFECT_REGISTER.md references a Class#method that does not exist in the test tree.
set -euo pipefail
REG=docs/implementation/mesh/DEFECT_REGISTER.md
T=mobile-app/app/src/test/java
bad=0
while read -r ref; do
  cls="${ref%%#*}"; m="${ref##*#}"
  f=$(find "$T" -name "${cls}.kt" | head -1)
  if [ -z "$f" ] || ! grep -qE "fun +\`?${m}\b" "$f"; then echo "MISSING TEST: $ref"; bad=1; fi
done < <(grep -oE '[A-Z][A-Za-z0-9]+#[A-Za-z0-9_ ]+' "$REG" | sed 's/ *$//')
exit $bad

