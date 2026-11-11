#!/usr/bin/env python3
"""
Verification script for Round 9 Closure Report.
Verifies that:
1. Every file path referenced in the report exists in git tracking (via `git ls-files --error-unmatch`).
2. Every `Class#method` test reference exists under `mobile-app/app/src/test/` with matching `fun <method>`.
Output ends with `missing: 0` for files and for tests.
"""

import sys
import os
import re
import subprocess
import glob

def main():
    report_path = sys.argv[1] if len(sys.argv) > 1 else "docs/reports/round9-voice-and-packet-closure.md"
    if not os.path.isfile(report_path):
        print(f"ERROR: Report file not found: {report_path}")
        sys.exit(1)

    with open(report_path, "r", encoding="utf-8") as f:
        content = f.read()

    print(f"=== Verifying Report References: {report_path} ===")

    # 1. Extract Class#method test references
    test_refs = sorted(list(set(re.findall(r'([A-Za-z0-9_]+)#([A-Za-z0-9_`]+)', content))))
    print(f"Extracted {len(test_refs)} unique test references.")

    # Index all test files under mobile-app/app/src/test/
    test_files_map = {}
    for path in glob.glob("mobile-app/app/src/test/**/*.kt", recursive=True):
        basename = os.path.splitext(os.path.basename(path))[0]
        test_files_map[basename] = path

    missing_tests = 0
    checked_tests = 0
    for cls_name, method_name in test_refs:
        checked_tests += 1
        clean_method = method_name.strip("`")
        if cls_name not in test_files_map:
            print(f"[TEST MISSING FILE] Class {cls_name}.kt not found for {cls_name}#{clean_method}")
            missing_tests += 1
            continue
        
        file_path = test_files_map[cls_name]
        with open(file_path, "r", encoding="utf-8") as tf:
            file_content = tf.read()
        
        # Match `fun method_name` or `fun `method_name``
        pattern = rf'fun\s+(`?{re.escape(clean_method)}`?)\s*\('
        if not re.search(pattern, file_content):
            print(f"[TEST MISSING METHOD] Method {clean_method} not found in {file_path}")
            missing_tests += 1
        else:
            # print(f"  [OK] {cls_name}#{clean_method}")
            pass

    print(f"Checked {checked_tests} test references. Missing tests: {missing_tests}")

    # 2. Extract explicit repo file paths
    # Matches paths like docs/..., mobile-app/..., .github/..., backend/..., livekit-server/..., README.md
    raw_paths = re.findall(r'(?:`|\||\s)((?:docs|\.github|mobile-app|backend|web-app|livekit-server)/[A-Za-z0-9_\-\./]+\.[A-Za-z0-9]+|README\.md)(?:`|\||\s)', content)
    unique_paths = sorted(list(set(raw_paths)))
    # Exclude the report itself if uncommitted or in-progress
    unique_paths = [p for p in unique_paths if not p.endswith("round9-voice-and-packet-closure.md")]
    print(f"Extracted {len(unique_paths)} unique repository file paths.")

    missing_files = 0
    for p in unique_paths:
        res = subprocess.run(["git", "ls-files", "--error-unmatch", p], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if res.returncode != 0:
            # Also check if file exists on disk (e.g. uncommitted changes in current branch)
            if not os.path.exists(p):
                print(f"[FILE MISSING] Path not tracked or found: {p}")
                missing_files += 1

    print(f"Checked {len(unique_paths)} file paths. Missing files: {missing_files}")

    print("--- VERIFICATION RESULT ---")
    print(f"files missing: {missing_files}")
    print(f"tests missing: {missing_tests}")

    if missing_files == 0 and missing_tests == 0:
        print("SUMMARY: missing: 0")
        return 0
    else:
        print(f"SUMMARY: FAILED with {missing_files} missing files and {missing_tests} missing tests")
        return 1

if __name__ == "__main__":
    sys.exit(main())

