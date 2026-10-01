#!/usr/bin/env bats

# Tests the packaged prompt-optimizer launchers of both harness images against a fixture campaign.

# Bats invokes this before each test to create a Git project that holds the fixture campaign.
#
# Side effects:
#   Sets test-scoped environment variables and creates their directories under CAT work.
setup()
{
	export GENERATED_RUNTIME_DIRECTORY="${BATS_TEST_DIRNAME}/../../../target/runtime"
	TEST_ROOT="$("${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/workflow-temp" create prompt-optimizer)"
	export TEST_ROOT
	export HOST_HOME="${TEST_ROOT}/host-home"
	export PROJECT="${TEST_ROOT}/project"
	fixture="${BATS_TEST_DIRNAME}/../../../../cli/src/test/resources/optimizer/cli/campaign.json"
	mkdir -p "${HOST_HOME}" "${PROJECT}/.cat/work/optimizer/fixture-campaign"
	git -C "${PROJECT}" init -q
	cp "${fixture}" "${PROJECT}/.cat/work/optimizer/fixture-campaign/campaign.json"
}

# Removes the test's CAT-work fixture after its last assertion completes.
#
# Side effects:
#   Deletes the temporary directory created by setup.
teardown()
{
	if [ -n "${TEST_ROOT:-}" ]; then
		"${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/workflow-temp" delete "${TEST_ROOT}"
	fi
}

# Runs a packaged prompt-optimizer launcher in the fixture project.
#
# Arguments:
#   $1 - Harness whose image holds the launcher.
#   $@ - Launcher arguments, after the harness.
#
# Output:
#   Sets bats' status and output variables.
run_optimizer()
{
	harness="$1"
	shift
	run env --chdir="${PROJECT}" -u CLAUDE_CODE_SESSION_ID -u CODEX_THREAD_ID HOME="${HOST_HOME}" \
		"${GENERATED_RUNTIME_DIRECTORY}/${harness}/client/bin/prompt-optimizer" "$@"
}

# Prints a checksum of every file in the fixture project, so a test can prove that nothing changed.
tree_checksum()
{
	(cd "${PROJECT}" && find . -path ./.git -prune -o -type f -print | LC_ALL=C sort | xargs sha256sum | sha256sum)
}

@test "packaged Claude prompt-optimizer prints the status JSON of a fixture campaign" {
	# Requirement: R-I4
	run_optimizer claude status fixture-campaign
	[ "${status}" -eq 0 ]
	printf '%s\n' "${output}" | jq -e '.campaign == "fixture-campaign" and .harness == "claude" and
		(.choices | type == "array") and (.summary | type == "string") and (.status | type == "string")'
}

@test "packaged Codex prompt-optimizer prints the status JSON of a fixture campaign" {
	# Requirement: R-I4
	run_optimizer codex status fixture-campaign
	[ "${status}" -eq 0 ]
	printf '%s\n' "${output}" | jq -e '.campaign == "fixture-campaign" and .harness == "claude" and
		(.choices | type == "array") and (.summary | type == "string") and (.status | type == "string")'
}

@test "packaged prompt-optimizer launchers reject invalid input with exit code 2" {
	# Requirement: R-I2
	for harness in claude codex; do
		run_optimizer "${harness}" choose fixture-campaign keep-improvement
		[ "${status}" -eq 2 ]
		printf '%s\n' "${output}" | jq -e '.status == "invalid-input"'
	done
}

@test "packaged Codex prompt-optimizer refuses a Claude campaign before changing anything" {
	# Requirement: R-L1
	before="$(tree_checksum)"
	run_optimizer codex run fixture-campaign
	[ "${status}" -eq 1 ]
	printf '%s\n' "${output}" | jq -e '.status == "refused" and (.message | contains("claude"))'
	[ "$(tree_checksum)" = "${before}" ]
}
