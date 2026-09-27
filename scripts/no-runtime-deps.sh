#!/bin/sh
# Asserts the published package declares no runtime dependencies. Not `npm audit --omit=dev`:
# that walks the whole workspace, example apps' Expo tooling included, which no consumer installs.

set -e

manifest="packages/core/package.json"
count=$(node -p "Object.keys(require('./$manifest').dependencies || {}).length")

if [ "$count" -ne 0 ]; then
  echo "The package now declares $count runtime dependencies." >&2
  echo "Audit and license-check them explicitly, then update this gate." >&2
  node -p "Object.keys(require('./$manifest').dependencies).join('\n')" >&2
  exit 1
fi

printf 'react-native-pose-detection declares no runtime dependencies, so a consumer installs none.\n'
