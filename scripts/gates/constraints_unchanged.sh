#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
git show main:architecture/CONSTRAINTS.md | cmp - architecture/CONSTRAINTS.md
echo "CONSTRAINTS UNCHANGED"
