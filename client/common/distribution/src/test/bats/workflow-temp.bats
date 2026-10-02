#!/usr/bin/env bats

# Tests that each image's packaged workflow-temp launcher finds, in later invocations, the directories that earlier
# invocations created.

# Bats invokes this before each test to create its fixture directory under the project's .cat/work/temp.
#
# Side effects:
#   Sets GENERATED_RUNTIME_DIRECTORY and TEST_ROOT.
setup()
{
	export GENERATED_RUNTIME_DIRECTORY="${BATS_TEST_DIRNAME}/../../../target/runtime"
	TEST_ROOT="$("${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/workflow-temp" create workflow-temp)"
	export TEST_ROOT
}

# Removes the test's fixture directory after its last assertion completes.
#
# Side effects:
#   Deletes the temporary directory created by setup.
teardown()
{
	if [ -n "${TEST_ROOT:-}" ]; then
		"${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/workflow-temp" delete "${TEST_ROOT}"
	fi
}

# Verifies that one image's workflow-temp root is an existing directory that holds the test's fixture directory, which
# the Codex image's launcher created in an earlier invocation.
#
# Arguments:
#   $1 - Harness whose image holds the launcher.
assert_root_holds_earlier_directories()
{
	root="$("${GENERATED_RUNTIME_DIRECTORY}/$1/client/bin/workflow-temp" root)"
	[ -d "${root}" ]
	[[ "${TEST_ROOT}" == "${root}/"* ]]
}

# Verifies that one image's workflow-temp handoff moves a directory that an earlier invocation created to the retained
# location and prints that location.
#
# Arguments:
#   $1 - Harness whose image holds the launcher.
assert_handoff_moves_earlier_directory()
{
	launcher="${GENERATED_RUNTIME_DIRECTORY}/$1/client/bin/workflow-temp"
	created="$("${launcher}" create workflow-temp)"
	printf 'retained\n' > "${created}/evidence"
	retained="${TEST_ROOT}/retained/evidence"

	handed_off="$("${launcher}" handoff "${created}" "${retained}")"

	[ "${handed_off}" = "${retained}" ]
	[ ! -e "${created}" ]
	[ "$(cat "${retained}/evidence")" = "retained" ]
}

@test "packaged Claude workflow-temp root holds directories from earlier invocations" {
	assert_root_holds_earlier_directories claude
}

@test "packaged Codex workflow-temp root holds directories from earlier invocations" {
	assert_root_holds_earlier_directories codex
}

@test "packaged Claude workflow-temp handoff moves a directory created by an earlier invocation" {
	assert_handoff_moves_earlier_directory claude
}

@test "packaged Codex workflow-temp handoff moves a directory created by an earlier invocation" {
	assert_handoff_moves_earlier_directory codex
}
