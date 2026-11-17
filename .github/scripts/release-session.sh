#!/usr/bin/env bash
# Runs the authenticated release-worker session lifecycle for one GitHub Actions job.
set -euo pipefail

# The pinned upstream release whose native executable supports this workflow's session commands.
readonly UPTERM_VERSION='v0.38.0'
# The Upterm deadline for a client to join; readiness waits allow one extra minute for workflow scheduling.
readonly JOIN_TIMEOUT_SECONDS=600
readonly READINESS_GRACE_SECONDS=60

# Prints a diagnostic and returns a failure status to the caller.
#
# Arguments:
#   message: The actionable diagnostic to print.
#
# Output: Writes the diagnostic to standard error.
# Exit status: Always returns 1.
fail() {
	printf 'release-session: %s\n' "$1" >&2
	return 1
}

# Prints the supported invocation forms for this release-session entry point.
#
# Output: Writes usage to standard error.
# Exit status: Always returns 2.
usage() {
	cat >&2 <<'USAGE'
Usage:
  release-session.sh start SESSION_DIR PLATFORM PUBLIC_KEY RUN_ID WORKSPACE_COMMIT IDLE_SECONDS WORKSPACE [PRIMARY_URL]
  release-session.sh wait-ready SESSION_DIR RUN_ID WORKSPACE_COMMIT WORKSPACE
  release-session.sh hold SESSION_DIR
  release-session.sh cleanup SESSION_DIR
USAGE
	return 2
}

# Creates one private session directory and records the run identity that owns it.
#
# Arguments:
#   session_dir: A new absolute directory below this job's RUNNER_TEMP.
#   run_id: The GitHub run, attempt, job, and platform identity for this session.
#
# Side effects: Creates the session directory and its ownership marker.
# Errors: Fails if the directory is not absolute, already exists, or cannot be created.
# Exit status: Returns 0 when the directory is created, or 1 on failure.
create_session_directory() {
	local session_dir=$1
	local run_id=$2
	if [[ "$session_dir" != /* ]]; then
		fail 'the session directory must be an absolute path.'
		return 1
	fi
	if [[ -e "$session_dir" || -L "$session_dir" ]]; then
		fail 'the session directory already exists; use a fresh RUNNER_TEMP child.'
		return 1
	fi
	if ! mkdir -m 700 -- "$session_dir"; then
		fail 'could not create the session directory.'
		return 1
	fi
	printf '%s\n' "$run_id" > "$session_dir/.release-session-owner"
}

# Downloads and starts the pinned Upterm host for one native release worker.
#
# Arguments:
#   session_dir: A new absolute directory below this job's RUNNER_TEMP.
#   platform: One of the five release matrix platform identifiers.
#   public_key: The single public key authorized to join this session.
#   run_id: The GitHub run, attempt, job, and platform identity for this session.
#   workspace_commit: The commit expected in the job workspace.
#   idle_seconds: The shell prompt idle limit in seconds; active commands may run longer.
#   workspace: The checked-out project directory the remote shell must open.
#   primary_url: Optional primary archive URL, used by preflight to exercise the API fallback.
#
# Output: Prints the SSH destination from the Upterm session record.
# Side effects: Downloads the verified Upterm archive, writes session metadata, and starts the detached host. If metadata publication fails after detach, stops the named host and waits for its terminal state.
# Errors: Fails for invalid arguments, a missing or invalid key, an unsupported platform, or setup and metadata publication failures.
# Exit status: Returns 0 after the host publishes its session details, or 1 on failure.
start_session() {
	local session_dir=$1
	local platform=$2
	local public_key=$3
	local run_id=$4
	local workspace_commit=$5
	local idle_seconds=$6
	local workspace=$7
	local primary_url=${8:-"https://github.com/owenthereal/upterm/releases/download/${UPTERM_VERSION}/"}
	local archive_name
	local archive_sha256
	local actual_sha256
	local executable_name
	local archive
	local key_file
	local workspace_quoted
	local upterm_quoted
	local upterm_executable
	local session_name
	local ssh_command
	local script_directory
	local entry_script
	local upterm_release_metadata
	local asset_api_url
	local session_json_tmp
	local session_json_publish_tmp
	local metadata_tmp
	local cleanup_trap

	case "$platform" in
		linux-x64)
			archive_name='upterm_linux_amd64.tar.gz'
			archive_sha256='d5eff68db0ac596277f85ac7bf3443558f8463cd34db2b40b026899fda6b78c9'
			executable_name='upterm'
			;;
		linux-arm64)
			archive_name='upterm_linux_arm64.tar.gz'
			archive_sha256='fcd47353353bb39f96dd56418cc188643de6d227f4bb1e303efb0398c7dfa93d'
			executable_name='upterm'
			;;
		macos-x64)
			archive_name='upterm_darwin_amd64.tar.gz'
			archive_sha256='aaca2b92db585c53ed7d7bff723f40a19d2ae8b0c09fa52689bb077a0f64951b'
			executable_name='upterm'
			;;
		macos-arm64)
			archive_name='upterm_darwin_arm64.tar.gz'
			archive_sha256='8258498167a8e7671680699f765c4cdf6814fe957256ac3fc7452cbd5ad1366f'
			executable_name='upterm'
			;;
		windows-x64)
			archive_name='upterm_windows_amd64.tar.gz'
			archive_sha256='5018c29562341f4982135145b3ef9384e24b7bdf39d4cbc263f9e9ba4db9a698'
			executable_name='upterm.exe'
			;;
		*)
			fail "unsupported release platform '${platform}'."
			return 1
			;;
	esac

	if [[ -z "$public_key" ]]; then
		fail 'the authorized public key is empty.'
		return 1
	fi
	if [[ ! "$idle_seconds" =~ ^[1-9][0-9]*$ ]]; then
		fail 'the shell idle limit must be a positive number of seconds.'
		return 1
	fi
	if [[ -z "$run_id" || -z "$workspace_commit" || -z "$workspace" ]]; then
		fail 'the run identity, workspace commit, and workspace path are required.'
		return 1
	fi

	create_session_directory "$session_dir" "$run_id"
	key_file="${session_dir}/authorized_keys"
	archive="${session_dir}/${archive_name}"
	upterm_release_metadata="${session_dir}/upterm-release.json"
	printf '%s\n' "$public_key" > "$key_file"
	if [[ "$(awk 'NF { count++ } END { print count + 0 }' "$key_file")" -ne 1 ]]; then
		fail 'the authorized public key must contain exactly one nonempty line.'
		return 1
	fi
	if ! ssh-keygen -lf "$key_file" >/dev/null; then
		fail 'the authorized public key is not a valid OpenSSH public key.'
		return 1
	fi

	if ! curl -fsSL --retry 3 --retry-all-errors -o "$archive" \
		"${primary_url}${archive_name}"; then
		printf 'release-session: direct download failed; trying the pinned GitHub release asset API.\n' >&2
		curl -fsSL --retry 3 --retry-all-errors -o "$upterm_release_metadata" \
			"https://api.github.com/repos/owenthereal/upterm/releases/tags/${UPTERM_VERSION}"
		asset_api_url=$(jq -er --arg name "$archive_name" '.assets[] | select(.name == $name) | .url' \
			"$upterm_release_metadata")
		curl -fsSL --retry 3 --retry-all-errors -H 'Accept: application/octet-stream' -o "$archive" "$asset_api_url"
	fi
	actual_sha256=$(sha256sum "$archive")
	actual_sha256=${actual_sha256%% *}
	if [[ "$actual_sha256" != "$archive_sha256" ]]; then
		fail "the downloaded archive checksum does not match the pinned SHA-256 for ${archive_name}."
		return 1
	fi
	tar -xzf "$archive" -C "$session_dir"
	"${session_dir}/${executable_name}" version

	script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
	entry_script="${script_directory}/release-session.sh"
	if [[ ! -f "$entry_script" || ! -f "${script_directory}/../release-session-known-hosts" ]]; then
		fail 'the release-session entry script could not locate its known-hosts file.'
		return 1
	fi
	printf -v workspace_quoted '%q' "$workspace"
	upterm_executable="${session_dir}/${executable_name}"
	printf -v upterm_quoted '%q' "$upterm_executable"
	cat > "${session_dir}/bashrc" <<BASHRC
trap 'printf "release-session: startup interrupted before the guest timeout policy was installed.\\n" >&2; exit 130' INT TERM HUP
if [[ -z "\${UPTERM_SESSION_NAME:-}" ]]; then
	printf 'release-session: Upterm did not provide a nonempty session name.\\n' >&2
	exit 1
fi
session_name=\$UPTERM_SESSION_NAME
while :; do
	if session_info=\$(${upterm_quoted} session info "\$session_name" -o json); then
		:
	else
		printf 'release-session: could not query the named Upterm session.\\n' >&2
		exit 1
	fi
	if session_state=\$(jq -er --arg session_name "\$session_name" '
		if type == "object" then
			if .name == \$session_name then
				if .status == "ready" then
					if has("firstGuestJoinedAt") then
						.firstGuestJoinedAt
						| if type == "string" then
							if length > 0 then "joined" else error("firstGuestJoinedAt must be nonempty") end
						else error("firstGuestJoinedAt must be a string") end
					else "waiting" end
				else error("session is not ready") end
			else error("session info name does not match") end
		else error("session info must be a JSON object") end' <<< "\$session_info"); then
		:
	else
		printf 'release-session: Upterm returned invalid session identity or readiness data.\\n' >&2
		exit 1
	fi
	if [[ "\$session_state" == joined ]]; then
		break
	fi
	sleep 2
done
export -n TMOUT
readonly TMOUT=$idle_seconds
PS1="SESSION$ "
unset PROMPT_COMMAND
cd -- $workspace_quoted
trap - INT TERM HUP
BASHRC
	session_json_tmp="${session_dir}/session.json.tmp"
	session_json_publish_tmp="${session_dir}/session.json.publish.tmp"
	printf -v cleanup_trap 'cleanup_unpublished_host %q %q %q' "$upterm_executable" "$session_json_tmp" "$session_dir"
	trap "$cleanup_trap" EXIT
	trap 'exit 130' INT TERM HUP
	"$upterm_executable" host --detach --accept --output json \
		--join-timeout "$((JOIN_TIMEOUT_SECONDS / 60))m" \
		--authorized-keys "$key_file" --known-hosts "${script_directory}/../release-session-known-hosts" \
		-- bash --rcfile "${session_dir}/bashrc" -i > "$session_json_tmp"
	if session_name=$(jq -er '.name | strings | select(length > 0)' "$session_json_tmp"); then
		:
	else
		fail 'Upterm did not return a nonempty session name.'
		return 1
	fi
	printf -v cleanup_trap 'cleanup_started_host %q %q %q' "$upterm_executable" "$session_name" "$session_dir"
	trap "$cleanup_trap" EXIT
	if ssh_command=$(jq -er '.sshCommand | strings | select(length > 0)' "$session_json_tmp"); then
		:
	else
		fail 'Upterm did not return a nonempty SSH command.'
		return 1
	fi
	if cat "$session_json_tmp" > "$session_json_publish_tmp" && \
		mv -- "$session_json_publish_tmp" "${session_dir}/session.json"; then
		:
	else
		fail 'could not publish the Upterm session record.'
		return 1
	fi
	metadata_tmp="${session_dir}/name.tmp"
	if printf '%s\n' "$session_name" > "$metadata_tmp" && mv -- "$metadata_tmp" "${session_dir}/name"; then
		:
	else
		fail 'could not publish the Upterm session name.'
		return 1
	fi
	metadata_tmp="${session_dir}/ssh-command.tmp"
	if printf '%s\n' "$ssh_command" > "$metadata_tmp" && mv -- "$metadata_tmp" "${session_dir}/ssh-command"; then
		:
	else
		fail 'could not publish the Upterm SSH command.'
		return 1
	fi
	metadata_tmp="${session_dir}/workspace-commit.tmp"
	if printf '%s\n' "$workspace_commit" > "$metadata_tmp" && mv -- "$metadata_tmp" "${session_dir}/workspace-commit"; then
		:
	else
		fail 'could not publish the workspace commit.'
		return 1
	fi
	if rm -f -- "$session_json_tmp"; then
		:
	else
		fail 'could not remove the temporary Upterm session record.'
		return 1
	fi
	trap - EXIT INT TERM HUP
	printf '=== REMOTE SESSION ===\n'
	printf '%s\n' "$ssh_command"
	printf '=== END REMOTE SESSION ===\n'
}

# Stops a detached host and waits for that exact host to reach its terminal state after startup publication fails.
#
# Arguments:
#   upterm_executable: The pinned executable that created the detached host.
#   session_name: The returned host identity to stop and wait for.
#   session_dir: The owned directory that retains cleanup diagnostics.
#
# Side effects: Stops and waits for the named host, recording command output in the session directory.
# Errors: Reports when Upterm cannot confirm the host's terminal state.
# Exit status: Returns 0 when terminal state is confirmed, or 1 otherwise.
cleanup_started_host() {
	local upterm_executable=$1
	local session_name=$2
	local session_dir=$3
	local cleanup_log="${session_dir}/startup-cleanup.log"
	local stop_status
	local wait_status

	set +e
	"$upterm_executable" session stop "$session_name" >> "$cleanup_log" 2>&1
	stop_status=$?
	"$upterm_executable" session wait "$session_name" >> "$cleanup_log" 2>&1
	wait_status=$?
	set -e
	if [[ "$stop_status" == 0 ]]; then
		:
	else
		printf 'release-session: stopping Upterm session %s exited with status %s.\\n' "$session_name" "$stop_status" >&2
	fi
	if [[ "$wait_status" == 0 ]]; then
		:
	else
		printf 'release-session: could not confirm terminal state for Upterm session %s; see %s.\\n' \
			"$session_name" "$cleanup_log" >&2
		return 1
	fi
}

# Cleans up an Upterm host when startup exits before publishing all session metadata.
#
# Arguments:
#   upterm_executable: The pinned executable that created the detached host.
#   session_response: The file containing Upterm's detached host response.
#   session_dir: The owned directory that retains cleanup diagnostics.
#
# Side effects: Stops and waits for the returned host when its name is present in the response.
# Exit status: Returns 0 after cleanup or when no returned host identity is available.
cleanup_unpublished_host() {
	local upterm_executable=$1
	local session_response=$2
	local session_dir=$3
	local session_name
	if [[ -s "$session_response" ]]; then
		:
	else
		session_response="${session_dir}/session.json"
	fi
	if session_name=$(jq -er '.name | strings | select(length > 0)' "$session_response" 2>/dev/null); then
		cleanup_started_host "$upterm_executable" "$session_name" "$session_dir" || true
	fi
}

# Waits until the authenticated client writes this run's identity and workspace commit into the checked-out workspace.
#
# Arguments:
#   session_dir: The owned session directory created by start.
#   run_id: The GitHub run, attempt, job, and platform identity expected in the marker.
#   workspace_commit: The commit expected in the job workspace.
#   workspace: The checked-out project directory that contains the client-written marker.
#
# Side effects: Removes the matching readiness marker after validating it.
# Errors: Fails when no session was started, the marker is mismatched, or readiness exceeds the join deadline.
# Exit status: Returns 0 after the marker matches, or 1 on failure.
wait_ready() {
	local session_dir=$1
	local run_id=$2
	local workspace_commit=$3
	local workspace=$4
	local readiness_file="${workspace}/.release-session-ready"
	local deadline=$((SECONDS + JOIN_TIMEOUT_SECONDS + READINESS_GRACE_SECONDS))
	local marker_line
	local -a marker_lines=()

	if [[ ! -s "${session_dir}/name" || ! -s "${session_dir}/session.json" ]]; then
		fail 'no live-session identity was published; readiness cannot be established.'
		return 1
	fi
	if [[ ! -f "${session_dir}/.release-session-owner" || \
		"$(cat "${session_dir}/.release-session-owner")" != "$run_id" ]]; then
		fail 'the session directory belongs to another run identity.'
		return 1
	fi
	while [[ ! -e "$readiness_file" ]]; do
		if (( SECONDS >= deadline )); then
			fail 'the authenticated session did not publish its readiness marker before the join deadline.'
			return 1
		fi
		sleep 2
	done
	if [[ ! -f "$readiness_file" || -L "$readiness_file" ]]; then
		fail 'the readiness marker must be a regular file in the job workspace.'
		return 1
	fi
	while IFS= read -r marker_line || [[ -n "$marker_line" ]]; do
		marker_lines+=("$marker_line")
	done < "$readiness_file"
	if [[ "${#marker_lines[@]}" -ne 2 || "${marker_lines[0]}" != "$run_id" || \
		"${marker_lines[1]}" != "$workspace_commit" ]]; then
		fail 'the readiness marker does not identify this run and workspace commit.'
		return 1
	fi
	rm -f -- "$readiness_file"
	printf 'Authenticated session verified workspace identity %s at commit %s.\n' "$run_id" "$workspace_commit"
}

# Waits for the Upterm session to end before writing its terminal receipt.
#
# Arguments:
#   session_dir: The owned session directory created by start.
#
# Output: Writes Upterm wait output to the session log.
# Side effects: Writes a JSON terminal receipt and stops an already-ended Upterm session.
# Errors: Fails immediately when startup did not publish a session identity, or when Upterm wait fails.
# Exit status: Returns 0 only after Upterm reports the session ended, or 1 otherwise.
hold_session() {
	local session_dir=$1
	local session_name
	local run_id
	local workspace_commit
	local wait_status
	local terminal_observed
	local upterm_executable
	if [[ ! -s "${session_dir}/name" || ! -s "${session_dir}/session.json" ]]; then
		fail 'no Upterm session was started; refusing to wait or hang.'
		return 1
	fi
	if [[ ! -f "${session_dir}/.release-session-owner" ]]; then
		fail 'the session directory has no ownership record.'
		return 1
	fi
	session_name=$(cat "${session_dir}/name")
	run_id=$(cat "${session_dir}/.release-session-owner")
	workspace_commit=$(cat "${session_dir}/workspace-commit")
	upterm_executable=$(find "$session_dir" -maxdepth 1 -type f \( -name upterm -o -name upterm.exe \) -print -quit)
	if [[ -z "$upterm_executable" ]]; then
		fail 'the pinned Upterm executable is missing from the owned session directory.'
		return 1
	fi
	set +e
	"$upterm_executable" session wait "$session_name" > "${session_dir}/session-wait.log" 2>&1
	wait_status=$?
	set -e
	if [[ "$wait_status" -eq 0 ]]; then
		terminal_observed=true
		"$upterm_executable" session stop "$session_name" >> "${session_dir}/session-wait.log" 2>&1 || true
	else
		terminal_observed=false
	fi
	jq -n --arg run_id "$run_id" --arg session_name "$session_name" --arg workspace_commit "$workspace_commit" \
		--argjson wait_exit_status "$wait_status" --argjson terminal_observed "$terminal_observed" \
		'{format_version: 1, run_id: $run_id, workspace_commit: $workspace_commit, session_name: $session_name,
		 wait_exit_status: $wait_exit_status, terminal_observed: $terminal_observed}' \
		> "${session_dir}/terminal-receipt.json.tmp"
	mv -- "${session_dir}/terminal-receipt.json.tmp" "${session_dir}/terminal-receipt.json"
	if [[ "$wait_status" -ne 0 ]]; then
		fail "Upterm session wait exited with status ${wait_status}; terminal state is unconfirmed."
		return 1
	fi
}

# Removes only a session directory carrying this script's ownership marker.
#
# Arguments:
#   session_dir: The owned session directory created by start.
#
# Side effects: Recursively removes the owned session directory.
# Errors: Fails rather than removing a directory without the ownership marker.
# Exit status: Returns 0 when the directory is absent or removed, or 1 on ownership mismatch.
cleanup_session() {
	local session_dir=$1
	if [[ ! -e "$session_dir" ]]; then
		return 0
	fi
	if [[ ! -d "$session_dir" || -L "$session_dir" || ! -f "${session_dir}/.release-session-owner" ]]; then
		fail 'refusing to remove a path that is not an owned session directory.'
		return 1
	fi
	rm -rf -- "$session_dir"
}

# Dispatches one supported session lifecycle operation.
#
# Arguments:
#   operation: start, wait-ready, hold, or cleanup.
#   operation_arguments: The complete arguments documented by usage for that operation.
#
# Exit status: Returns the selected operation's status, or 2 for an unsupported invocation.
main() {
	local operation=${1-}
	shift || true
	case "$operation" in
		start)
			[[ "$#" -eq 7 || "$#" -eq 8 ]] || { usage; return $?; }
			start_session "$@"
			;;
		wait-ready)
			[[ "$#" -eq 4 ]] || { usage; return $?; }
			wait_ready "$@"
			;;
		hold)
			[[ "$#" -eq 1 ]] || { usage; return $?; }
			hold_session "$@"
			;;
		cleanup)
			[[ "$#" -eq 1 ]] || { usage; return $?; }
			cleanup_session "$@"
			;;
		*)
			usage
			return $?
			;;
	esac
}

main "$@"
