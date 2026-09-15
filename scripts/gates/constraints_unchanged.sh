#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
git show main:CONSTRAINTS.md | cmp - CONSTRAINTS.md
echo "CONSTRAINTS UNCHANGED"
