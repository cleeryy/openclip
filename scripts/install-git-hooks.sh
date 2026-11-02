#!/usr/bin/env sh
# Point this clone at the repository hooks (.githooks/).
# Safe to re-run. See CONTRIBUTING.md §4 for the commit format it enforces.
set -eu

cd "$(dirname "$0")/.."
git config core.hooksPath .githooks
chmod +x .githooks/commit-msg
echo "Git hooks enabled: core.hooksPath=.githooks"
echo "Commit format: <type>(<scope>): <imperative description>"
