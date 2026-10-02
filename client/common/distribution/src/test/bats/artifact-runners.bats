#!/usr/bin/env bats

# Tests packaged Claude and Codex runners for isolated harness protocol and artifact boundaries.

# Bats invokes this before each test to create its fixture directory under the project's .cat/work/temp.
#
# Side effects:
#   Sets test-scoped environment variables and creates their directories under the fixture directory.
setup()
{
	export GENERATED_RUNTIME_DIRECTORY="${BATS_TEST_DIRNAME}/../../../target/runtime"
	TEST_ROOT="$("${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/workflow-temp" create artifact-runners)"
	export TEST_ROOT
	export FAKE_BIN="${TEST_ROOT}/bin"
	export HOST_HOME="${TEST_ROOT}/host-home"
	export RUN_ROOT="${TEST_ROOT}/runs"
	mkdir -p "${FAKE_BIN}" "${HOST_HOME}" "${RUN_ROOT}"
}

# Removes the test's fixture directory after its last runner assertion completes.
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

	created="$("${launcher}" create artifact-runners)"
	[[ "${created}" == "${root}/artifact-runners-"* ]]
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

@test "packaged workflow-temp help states that create prints the new directory's path" {
	run "${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/workflow-temp" --help
	[ "${status}" -eq 0 ]
	[[ "${output}" == *"Output: the new directory's absolute path, on one line."* ]]
}

@test "packaged workflow-temp rejects an invalid workflow name and deletion of its root" {
	launcher="${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/workflow-temp"
	run "${launcher}" create Uppercase
	[ "${status}" -ne 0 ]
	[[ "${output}" == *"Workflow name must match"* ]]
	root="$("${launcher}" root)"
	run "${launcher}" delete "${root}"
	[ "${status}" -ne 0 ]
	[[ "${output}" == *"must be below"* ]]
}

# Creates a minimal packaged plugin artifact for one harness runner.
#
# Arguments:
#   $1 - Harness identifier used in the artifact's plugin-manifest directory name.
#
# Output:
#   Writes the created artifact directory to standard output.
create_test_artifact()
{
	harness="$1"
	artifact="${TEST_ROOT}/${harness}-artifact"
	mkdir -p "${artifact}/.${harness}-plugin" "${artifact}/client/bin" "${artifact}/agents/${harness}"
	printf '{}\n' > "${artifact}/.${harness}-plugin/plugin.json"
	printf '1.0\n' > "${artifact}/client/VERSION"
	printf '# test agent\n' > "${artifact}/agents/${harness}/fixture.toml"
	printf '#!/bin/sh\nexit 0\n' > "${artifact}/client/bin/plugin-info"
	printf '#!/bin/sh\nexit 0\n' > "${artifact}/client/bin/session-start"
	chmod +x "${artifact}/client/bin/plugin-info" "${artifact}/client/bin/session-start"
	printf '%s\n' "${artifact}"
}

# Creates a source project that a harness runner copies into an isolated run directory.
#
# Output:
#   Writes the created source-project directory to standard output.
create_source_project()
{
	project="${TEST_ROOT}/source-project"
	mkdir -p "${project}/.git"
	printf 'source project\n' > "${project}/README.md"
	printf '%s\n' "${project}"
}

# Creates a fake Claude executable that records setup commands and, for each input line, streams one result followed
# by an idle session-state event.
#
# Side effects:
#   Creates FAKE_BIN/claude and writes its invocation log to FAKE_BIN/harness.log.
create_fake_claude_command()
{
	cat > "${FAKE_BIN}/claude" <<'SCRIPT'
#!/usr/bin/env sh
log="$(dirname "$0")/harness.log"
if [ "${1:-}" = "plugin" ]; then
	printf 'setup:%s:%s:%s\n' "$PWD" "${CLAUDE_CONFIG_DIR:-}" "$*" >> "${log}"
	exit 0
fi
printf 'serve:%s:%s:%s\n' "$PWD" "${CLAUDE_CONFIG_DIR:-}" "$*" >> "${log}"
turn=0
while IFS= read -r line; do
	turn=$((turn + 1))
	if [ "${turn}" -eq 1 ]; then
		printf '%s\n' '{"type":"result","result":"{\"selected_rules\":[]}"}'
	else
		printf '%s\n' '{"type":"result","result":"completed"}'
	fi
	printf '%s\n' '{"type":"system","subtype":"session_state_changed","state":"idle"}'
done
SCRIPT
	chmod +x "${FAKE_BIN}/claude"
}

# Prints the line number of the first runner event of one type in the output of the last bats run command.
#
# Arguments:
#   $1 - Event type, such as turn-completed.
#
# Output:
#   Writes the 1-based line number to standard output.
first_event_line()
{
	printf '%s\n' "${output}" | grep -n "\"type\":\"$1\"" | head -n 1 | cut -d: -f1
}

# Creates a fake Codex executable that records setup commands and emits minimal app-server protocol messages.
#
# Side effects:
#   Creates FAKE_BIN/codex and writes its invocation log to FAKE_BIN/harness.log.
create_fake_codex_command()
{
	cat > "${FAKE_BIN}/codex" <<'SCRIPT'
#!/usr/bin/env sh
log="$(dirname "$0")/harness.log"
if [ "${1:-}" = "plugin" ]; then
	printf 'setup:%s:%s:%s\n' "$PWD" "${CODEX_HOME:-}" "$*" >> "${log}"
	exit 0
fi
printf 'serve:%s:%s:%s\n' "$PWD" "${CODEX_HOME:-}" "$*" >> "${log}"
while IFS= read -r line; do
	printf 'input:%s\n' "${line}" >> "${log}"
	case "${line}" in
		*'"method":"thread/start"'*) printf '{"id":2,"result":{"thread":{"id":"thread-1"}}}\n' ;;
		*'"method":"hooks/list"'*) printf '%s\n' '{"id":3,"result":{"data":[{"hooks":[{"key":"cat@cat:hooks/hooks.json:session_start:0:0","trustStatus":"trusted"},{"key":"cat@cat:hooks/hooks.json:subagent_start:0:0","trustStatus":"trusted"}]}]}}' ;;
		*'"method":"turn/start"'*) printf '%s\n' '{"method":"item/completed","params":{"item":{"type":"agentMessage","text":"{\"selected_rules\":[]}"}}}' '{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"status":"completed"}}}' ;;
		*'"id":1'*) printf '{"id":1,"result":{}}\n' ;;
	esac
done
SCRIPT
	chmod +x "${FAKE_BIN}/codex"
}

@test "packaged Claude runner keeps one isolated process for multiple turns" {
	create_fake_claude_command
	artifact="$(create_test_artifact claude)"
	project="$(create_source_project)"
	mkdir -p "${HOST_HOME}/.claude"
	printf 'credential\n' > "${HOST_HOME}/.claude/.credentials.json"

	runner_command='printf "%s\n" \
"{\"type\":\"turn\",\"prompt\":\"first\"}" \
"{\"type\":\"turn\",\"prompt\":\"second\"}" \
"{\"type\":\"complete\"}" |
"$1" --artifact "$2" --project "$3" --run-root "$4" --executable claude --keep-run --install-cat'
	run env HOME="${HOST_HOME}" PATH="${FAKE_BIN}:${PATH}" sh -c "${runner_command}" \
		sh "${GENERATED_RUNTIME_DIRECTORY}/claude/client/bin/claude-runner" "${artifact}" "${project}" "${RUN_ROOT}"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"type":"started"'* ]]
	[ "$(first_event_line harness-event)" -lt "$(first_event_line turn-completed)" ]
	[ "$(grep -c '^serve:' "${FAKE_BIN}/harness.log")" -eq 1 ]
	grep -F "plugin marketplace add" "${FAKE_BIN}/harness.log"
	grep -F "plugin install cat@cat" "${FAKE_BIN}/harness.log"
	grep -F "${RUN_ROOT}/claude/" "${FAKE_BIN}/harness.log"
	[ -f "${HOST_HOME}/.claude/.credentials.json" ]
	[ -d "${RUN_ROOT}/claude" ]
	[ -d "${project}/.git" ]
	run_project="$(find "${RUN_ROOT}/claude" -mindepth 1 -maxdepth 1 -type d)/project"
	[ -d "${run_project}/.git" ]
}

@test "packaged Claude runner omits CAT unless explicitly requested" {
	create_fake_claude_command
	artifact="$(create_test_artifact claude)"
	project="$(create_source_project)"

	runner_command='printf "%s\n" "{\"type\":\"turn\",\"prompt\":\"first\"}" "{\"type\":\"complete\"}" |
"$1" --artifact "$2" --project "$3" --run-root "$4" --executable claude --keep-run'
	run env HOME="${HOST_HOME}" PATH="${FAKE_BIN}:${PATH}" sh -c "${runner_command}" \
		sh "${GENERATED_RUNTIME_DIRECTORY}/claude/client/bin/claude-runner" "${artifact}" "${project}" "${RUN_ROOT}"

	[ "${status}" -eq 0 ]
	[ "$(grep -c '^serve:' "${FAKE_BIN}/harness.log")" -eq 1 ]
	! grep -q '^setup:' "${FAKE_BIN}/harness.log"
	! grep -F -- '--add-dir' "${FAKE_BIN}/harness.log"
}

@test "packaged Codex runner keeps one isolated app-server thread for multiple turns" {
	create_fake_codex_command
	artifact="$(create_test_artifact codex)"
	project="$(create_source_project)"
	mkdir -p "${HOST_HOME}/.codex"
	printf 'credential\n' > "${HOST_HOME}/.codex/auth.json"

	runner_command='printf "%s\n" \
"{\"type\":\"turn\",\"prompt\":\"first\"}" \
"{\"type\":\"turn\",\"prompt\":\"second\"}" \
"{\"type\":\"complete\"}" |
"$1" --artifact "$2" --project "$3" --run-root "$4" --executable codex --keep-run --install-cat'
	run env HOME="${HOST_HOME}" PATH="${FAKE_BIN}:${PATH}" sh -c "${runner_command}" \
		sh "${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/codex-runner" "${artifact}" "${project}" "${RUN_ROOT}"

	[ "${status}" -eq 0 ]
	[[ "${output}" == *'"type":"turn-completed"'* ]]
	[ "$(first_event_line harness-event)" -lt "$(first_event_line turn-completed)" ]
	[ "$(grep -c '^serve:' "${FAKE_BIN}/harness.log")" -eq 1 ]
	grep -F "plugin marketplace add" "${FAKE_BIN}/harness.log"
	grep -F "plugin add cat@cat" "${FAKE_BIN}/harness.log"
	grep -F 'app-server --stdio --enable hooks' "${FAKE_BIN}/harness.log"
	grep -F "${RUN_ROOT}/codex/" "${FAKE_BIN}/harness.log"
	[ -f "${HOST_HOME}/.codex/auth.json" ]
}

@test "packaged Codex runner omits CAT unless explicitly requested" {
	create_fake_codex_command
	artifact="$(create_test_artifact codex)"
	project="$(create_source_project)"

	runner_command='printf "%s\n" "{\"type\":\"turn\",\"prompt\":\"first\"}" "{\"type\":\"complete\"}" |
"$1" --artifact "$2" --project "$3" --run-root "$4" --executable codex --keep-run'
	run env HOME="${HOST_HOME}" PATH="${FAKE_BIN}:${PATH}" sh -c "${runner_command}" \
		sh "${GENERATED_RUNTIME_DIRECTORY}/codex/client/bin/codex-runner" "${artifact}" "${project}" "${RUN_ROOT}"

	[ "${status}" -eq 0 ]
	[ "$(grep -c '^serve:' "${FAKE_BIN}/harness.log")" -eq 1 ]
	! grep -q '^setup:' "${FAKE_BIN}/harness.log"
	grep -F 'app-server --stdio --disable plugins' "${FAKE_BIN}/harness.log"
}
