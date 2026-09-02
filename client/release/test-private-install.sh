#!/usr/bin/env sh

# Verifies that the configured Git credential can read the private Commercial repository.

set -eu
[ "$#" -eq 3 ] || { echo "Usage: $0 COMMERCIAL_TREE commercial VERSION" >&2; exit 2; }
[ "$2" = commercial ] || exit 2
[ -d "$1" ] || { echo "Commercial staged tree is missing: $1" >&2; exit 1; }
if [ -z "${CAT_COMMERCIAL_TEST_REPOSITORY:-}" ]; then
	echo "Commercial private-repository test requires CAT_COMMERCIAL_TEST_REPOSITORY and configured Git credentials" >&2
	exit 77
fi
"$(dirname "$0")/test-staged-release.sh" "$1" commercial "$3"
if ! git ls-remote --heads "${CAT_COMMERCIAL_TEST_REPOSITORY}" >/dev/null 2>&1; then
	echo "Configured Git credentials cannot read the Commercial repository" >&2
	exit 1
fi
