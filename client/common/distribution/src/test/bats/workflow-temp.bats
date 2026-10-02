#!/usr/bin/env bats

# Tests that the packaged workflow-temp launcher keeps its directories between invocations.

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

@test "packaged workflow-temp keeps directories visible between launcher invocations" {
	launcher="${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/workflow-temp"
	root="$("${launcher}" root)"
	[ -d "${root}" ]
	[[ "${TEST_ROOT}" == "${root}/"* ]]

	created="$("${launcher}" create workflow-temp)"
	[[ "${created}" == "${root}/workflow-temp-"* ]]
	[ -d "${created}" ]
	printf 'retained\n' > "${created}/evidence"
	retained="${TEST_ROOT}/retained/evidence"
	handed_off="$("${launcher}" handoff "${created}" "${retained}")"
	[ "${handed_off}" = "${retained}" ]
	[ ! -e "${created}" ]
	[ -f "${retained}/evidence" ]
	"${launcher}" delete "${retained}"
	[ ! -e "${retained}" ]
}
