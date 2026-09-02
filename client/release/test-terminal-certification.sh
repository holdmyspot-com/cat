#!/usr/bin/env sh

# Runs a non-mutating terminal fixture through every locally available authenticated harness.

set -eu
[ "$#" -eq 3 ] || { echo "Usage: $0 PERSONAL_TREE COMMERCIAL_TREE VERSION" >&2; exit 2; }
personal_tree=$1
commercial_tree=$2
version=$3
if [ ! -d "$personal_tree" ] || [ ! -d "$commercial_tree" ]; then
	echo "Staged release tree is missing" >&2
	exit 1
fi
[ -n "$version" ] || { echo "Release version is required" >&2; exit 2; }

case "$(uname -s)" in
	Linux) operating_system=linux ;;
	Darwin) operating_system=macos ;;
	*) echo "Terminal certification does not support $(uname -s)" >&2; exit 77 ;;
esac
case "$(uname -m)" in
	x86_64|amd64) architecture=x86_64 ;;
	arm64|aarch64) architecture=arm64 ;;
	*) echo "Terminal certification does not support $(uname -m)" >&2; exit 77 ;;
esac
platform="${operating_system}-${architecture}"
temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/cat-terminal-certification.XXXXXX")

# Removes successful fixture evidence and retains failed evidence so the gate diagnostic identifies its exact cause.
#
# Output:
# Writes the retained evidence directory to standard error after a failed fixture.
#
# Exit status:
# Preserves the script's terminal status.
cleanup()
{
	status=$?
	trap - EXIT HUP INT TERM
	if [ "$status" -eq 0 ]; then
		rm -rf "$temporary_root"
	else
		printf '%s\n' "Terminal certification evidence retained at $temporary_root" >&2
	fi
	exit "$status"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
tested=0

run_fixture()
{
	harness=$1
	executable=$2
	model=$3
	effort=$4
	artifact="$commercial_tree/$harness/$platform"
	runner="$artifact/client/bin/${harness}-runner"
	if ! command -v "$executable" >/dev/null 2>&1; then
		echo "Skipping $harness terminal fixture: $executable is unavailable" >&2
		return
	fi
	if [ ! -x "$runner" ]; then
		echo "Skipping $harness terminal fixture: $runner is unavailable" >&2
		return
	fi
	project="$temporary_root/$harness-project"
	run_root="$temporary_root/$harness-runs"
	transcript="$temporary_root/$harness-transcript.jsonl"
	receipt="$temporary_root/$harness-receipt.json"
	mkdir -p "$project" "$run_root"
	if ! printf '%s\n%s\n' \
		'{"type":"turn","prompt":"Reply with exactly READY and make no file changes."}' \
		'{"type":"complete"}' |
		"$runner" --artifact "$artifact" --project "$project" --run-root "$run_root" --executable "$executable" \
			--model "$model" --effort "$effort" --install-cat --exclude-user-rules --keep-run --output "$transcript" \
			--receipt "$receipt"; then
		provider_stderr="$(find "$run_root/$harness" -path '*/artifacts/turn-1.stderr' -type f -print -quit)"
		if [ -n "$provider_stderr" ] && grep -Eq 'provider request failed with HTTP (429|5[0-9][0-9])' "$provider_stderr"; then
			echo "$harness terminal fixture provider is unavailable" >&2
			return 77
		fi
		return 1
	fi
	grep -F '"assistant_response":"READY"' "$transcript" >/dev/null ||
		{ echo "$harness terminal fixture did not return READY" >&2; exit 1; }
	if grep -F '"status":"failed"' "$transcript" >/dev/null; then
		echo "$harness terminal fixture reported a failed native hook" >&2
		exit 1
	fi
	[ -s "$receipt" ] || { echo "$harness terminal fixture did not write a receipt" >&2; exit 1; }
	tested=$((tested + 1))
	echo "Terminal certification passed: harness=$harness platform=$platform version=$version"
}

run_fixture codex codex gpt-5.6-luna medium
run_fixture claude claude haiku medium
[ "$tested" -gt 0 ] || { echo "Terminal certification requires an authenticated Codex or Claude executable" >&2; exit 77; }
