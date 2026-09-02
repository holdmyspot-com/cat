#!/usr/bin/env bats

# Verifies each packaged work-execute agent's completion and plan-rejection behavior through its live harness boundary.

fixture_root="${BATS_TEST_DIRNAME}/../resources/work-execute"

# Returns CAT's release-platform identifier for the current native host.
#
# Output:
# The release-platform identifier.
#
# Exit status:
# Returns nonzero when the host is unsupported.
platform()
{
	case "$(uname -s):$(uname -m)" in
		Linux:x86_64|Linux:amd64) printf '%s\n' linux-x86_64 ;;
		Linux:aarch64|Linux:arm64) printf '%s\n' linux-arm64 ;;
		Darwin:x86_64|Darwin:amd64) printf '%s\n' macos-x86_64 ;;
		Darwin:aarch64|Darwin:arm64) printf '%s\n' macos-arm64 ;;
		*) return 1 ;;
	esac
}

# Returns the staged artifact for one harness on the current native platform.
#
# Arguments:
# $1: Harness identifier.
#
# Output:
# The artifact directory.
artifact()
{
	harness=$1
	printf '%s/%s/%s\n' "${CAT_COMMERCIAL_STAGED_TREE}" "$harness" "$(platform)"
}

# Runs one work-execute fixture through a harness and retains its transcript and receipt in the Bats test directory.
#
# Arguments:
# $1: Harness executable name.
# $2: Fixture directory name.
# $3: Harness model name.
#
# Side effects:
# Creates the fixture project and harness-run directory below BATS_TEST_TMPDIR.
#
# Exit status:
# Returns the runner result after requiring its receipt.
run_plan()
{
	harness=$1
	fixture=$2
	model=$3
	artifact_path="$(artifact "$harness")"
	runner="$artifact_path/client/bin/${harness}-runner"
	project="${BATS_TEST_TMPDIR}/${harness}-${fixture}-project"
	run_root="${BATS_TEST_TMPDIR}/${harness}-${fixture}-runs"
	transcript="${BATS_TEST_TMPDIR}/${harness}-${fixture}-transcript.jsonl"
	receipt="${BATS_TEST_TMPDIR}/${harness}-${fixture}-receipt.json"
	mkdir -p "$project" "$run_root"
	cp "$fixture_root/$fixture/plan.md" "$project/plan.md"
	cp "$fixture_root/$fixture/work-execute-plan.json" "$project/work-execute-plan.json"
	set -o pipefail
	if ! printf '%s\n%s\n' \
		'{"type":"turn","prompt":"Read plan.md, then follow it and return the required JSON object."}' \
		'{"type":"complete"}' |
		"$runner" --artifact "$artifact_path" --project "$project" --run-root "$run_root" \
			--executable "$harness" --model "$model" --effort medium --install-cat --exclude-user-rules \
			--agent work-execute --work-execute-plan "$project/work-execute-plan.json" --keep-run \
			--output "$transcript" --receipt "$receipt"; then
		provider_stderr="$(find "$run_root/$harness" -path '*/artifacts/turn-1.stderr' -type f -print -quit)"
		if [ -n "$provider_stderr" ] && grep -Eq 'provider request failed with HTTP (429|5[0-9][0-9])' "$provider_stderr"; then
			if [ -n "${CAT_WORK_EXECUTE_PROVIDER_UNAVAILABLE_FILE:-}" ]; then
				printf '%s provider is unavailable\n' "$harness" >> "$CAT_WORK_EXECUTE_PROVIDER_UNAVAILABLE_FILE"
			fi
			return 77
		fi
		return 1
	fi
	if [ "$fixture" = complete ]; then
		[ -s "$receipt" ]
	fi
}

# Skips the live suite unless every supported harness and staged runner are available.
#
# Errors:
# Fails when enabled testing lacks a required staged artifact or executable.
require_actual_harnesses()
{
	[ "${CAT_RUN_ACTUAL_AGENT_TESTS:-}" = 1 ] || skip "Set CAT_RUN_ACTUAL_AGENT_TESTS=1 to run live work-execute fixtures"
	[ -d "${CAT_COMMERCIAL_STAGED_TREE:-}" ] || fail "CAT_COMMERCIAL_STAGED_TREE is required"
	for harness in codex claude; do
		command -v "$harness" >/dev/null 2>&1 || fail "$harness is unavailable"
		[ -x "$(artifact "$harness")/client/bin/${harness}-runner" ] || fail "$harness runner is unavailable"
	done
}

# Reports one harness-specific assertion failure while allowing the test to exercise the remaining harnesses.
#
# Arguments:
# $1: Harness identifier.
# $2: Description of the unmet observable result.
#
# Output:
# The failure description on Bats' diagnostic stream.
#
# Exit status:
# Always returns nonzero.
record_failure()
{
	printf '%s\n' "$1: $2" >&3
	return 1
}

# Checks that one harness completes the fixture and reports the runner-verified terminal status.
#
# Arguments:
# $1: Harness executable name.
# $2: Harness model name.
#
# Exit status:
# Returns zero only when the complete fixture's runner and terminal response succeed.
complete_plan_passed()
{
	harness=$1
	model=$2
	result=0
	run_plan "$harness" complete "$model" || result=$?
	if [ "$result" -eq 77 ]; then
		skip "$harness provider is temporarily unavailable"
	fi
	if [ "$result" -ne 0 ]; then
		record_failure "$harness" "complete fixture runner failed"
		return
	fi

	if ! rg -F '"status":"SUCCESS"' "${BATS_TEST_TMPDIR}/${harness}-complete-transcript.jsonl"; then
		if [ -f "${BATS_TEST_TMPDIR}/${harness}-complete-transcript.jsonl" ]; then
			cat "${BATS_TEST_TMPDIR}/${harness}-complete-transcript.jsonl" >&3
		fi
		record_failure "$harness" "complete fixture did not return SUCCESS"
		return
	fi
}

# Checks that one harness rejects the conflicting fixture before it creates its requested file.
#
# Arguments:
# $1: Harness executable name.
# $2: Harness model name.
#
# Exit status:
# Returns zero only when the ambiguous fixture leaves no result file and reports the required blocked status.
ambiguous_plan_rejected()
{
	harness=$1
	model=$2
	result=0
	run_plan "$harness" ambiguous "$model" || result=$?
	if [ "$result" -eq 77 ]; then
		skip "$harness provider is temporarily unavailable"
	fi
	if [ "$result" -ne 0 ]; then
		record_failure "$harness" "ambiguous fixture runner failed"
		return
	fi

	result="$(find "${BATS_TEST_TMPDIR}/${harness}-ambiguous-runs/$harness" -path '*/project/result.txt' -type f -print -quit)"
	if [ -n "$result" ]; then
		record_failure "$harness" "ambiguous fixture changed result.txt"
		return
	fi
	if ! rg -F '"status":"BLOCKED_PLAN_NOT_MECHANICAL"' "${BATS_TEST_TMPDIR}/${harness}-ambiguous-transcript.jsonl"; then
		record_failure "$harness" "ambiguous fixture did not return BLOCKED_PLAN_NOT_MECHANICAL"
		return
	fi
}

@test "work-execute completes the fully specified plan through each harness" {
	require_actual_harnesses
	failed=false
	for harness in codex claude; do
		if [ "$harness" = codex ]; then model=gpt-5.6-luna; else model=haiku; fi
		if ! complete_plan_passed "$harness" "$model"; then
			failed=true
		fi
	done
	[ "$failed" = false ]
}

@test "work-execute rejects an ambiguous plan without editing through each harness" {
	require_actual_harnesses
	failed=false
	for harness in codex claude; do
		if [ "$harness" = codex ]; then model=gpt-5.6-luna; else model=haiku; fi
		if ! ambiguous_plan_rejected "$harness" "$model"; then
			failed=true
		fi
	done
	[ "$failed" = false ]
}
