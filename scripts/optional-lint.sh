#!/bin/sh
# Usage: optional-lint.sh <tool> [args...]
# Runs a Homebrew linter (SwiftLint, SwiftFormat, ktlint) if installed, else skips: failing the
# hook over it teaches --no-verify. CI installs all three and runs them directly.

set -e

tool="$1"
shift

if ! command -v "$tool" >/dev/null 2>&1; then
  printf '  skipped %s: not installed. Run `brew install %s` to lint it before pushing.\n' "$tool" "$tool"
  exit 0
fi

exec "$tool" "$@"
