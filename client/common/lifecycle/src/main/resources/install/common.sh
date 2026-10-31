#!/usr/bin/env sh

CAT_VERSION="1.0"
CAT_RELEASE_TAG="v${CAT_VERSION}"
CAT_RELEASE_DOWNLOAD_URL="${CAT_RELEASE_BASE_URL:-https://github.com/holdmyspot-com/cat/releases/download/\
${CAT_RELEASE_TAG}}"
CAT_DOWNLOAD_TEMP_ROOT="${TMPDIR:-/tmp}"
CAT_DOWNLOAD_TEMP_DIRECTORY=""
CAT_REPOSITORY="${CAT_BOOTSTRAP_REPOSITORY:-holdmyspot-com/cat}"
# The exit status of an installer or uninstaller that stops before any change because it cannot write a path
CAT_NOT_WRITABLE_STATUS=77
# The command that repeats the running operation, printed when the operation fails after it began changing the harness
CAT_RECOVERY_COMMAND=""
# The operation that CAT_RECOVERY_COMMAND repeats, such as "installation", for the failure message
CAT_RECOVERY_OPERATION=""

# Returns whether the first dotted numeric version precedes the second.
#
# Arguments:
#   $1 - Dotted numeric version to compare.
#   $2 - Dotted numeric version to compare against.
#
# Exit status:
#   Returns zero when $1 precedes $2, one when it is equal to or follows $2, and two when either version is invalid.
cat_version_precedes()
{
	awk -v cat_left="$1" -v cat_right="$2" '
		BEGIN {
			if (cat_left !~ /^[0-9]+(\.[0-9]+)*$/ || cat_right !~ /^[0-9]+(\.[0-9]+)*$/)
				exit 2
			cat_left_count = split(cat_left, cat_left_parts, ".")
			cat_right_count = split(cat_right, cat_right_parts, ".")
			cat_count = cat_left_count
			if (cat_right_count > cat_count)
				cat_count = cat_right_count
			for (cat_index = 1; cat_index <= cat_count; ++cat_index) {
				cat_left_part = cat_index <= cat_left_count ? cat_left_parts[cat_index] + 0 : 0
				cat_right_part = cat_index <= cat_right_count ? cat_right_parts[cat_index] + 0 : 0
				if (cat_left_part < cat_right_part)
					exit 0
				if (cat_left_part > cat_right_part)
					exit 1
			}
			exit 1
		}'
}

# Returns whether a version uses dotted numeric components.
#
# Arguments:
#   $1 - Version to validate.
#
# Exit status:
#   Returns zero only for a dotted numeric version.
cat_is_dotted_numeric_version()
{
	if cat_version_precedes "$1" "$1"; then
		return
	else
		cat_version_status="$?"
	fi
	[ "${cat_version_status}" -eq 1 ]
}

# Migrates one harness's persistent CAT data to the artifact's current version.
#
# Arguments:
#   $1 - Writable CAT data directory for the harness being installed.
#   $2 - Directory containing this artifact's ordered migration registry and scripts.
#
# Side effects:
#   Runs each registry script for versions after the recorded migration version through CAT_VERSION, then records
#   CAT_VERSION only after every required script succeeds.
cat_migrate_data()
{
	cat_data_directory="$1"
	cat_migrations_directory="$2"
	cat_migration_version_file="${cat_data_directory}/migration-version"
	cat_migration_registry="${cat_migrations_directory}/registry.tsv"
	mkdir -p "${cat_data_directory}"
	[ -f "${cat_migration_registry}" ] || {
		echo "CAT migration registry is missing: ${cat_migration_registry}" >&2
		exit 1
	}
	if [ ! -f "${cat_migration_version_file}" ]; then
		cat_previous_version="${CAT_VERSION}"
		cat_first_install=true
	else
		cat_previous_version="$(tr -d '\r\n' < "${cat_migration_version_file}")"
		cat_first_install=false
	fi
	[ -n "${cat_previous_version}" ] || {
		echo "CAT migration version is empty: ${cat_migration_version_file}" >&2
		exit 1
	}
	cat_is_dotted_numeric_version "${cat_previous_version}" || {
		echo "CAT migration version must be dotted numeric text: ${cat_migration_version_file}" >&2
		exit 1
	}
	if cat_version_precedes "${CAT_VERSION}" "${cat_previous_version}"; then
		echo "CAT data was created by a newer version (${cat_previous_version}); install CAT ${cat_previous_version}" \
			"or newer." >&2
		exit 1
	fi
	cat_registry_previous_version=""
	while IFS='	' read -r cat_target_version cat_script; do
		[ -n "${cat_target_version}" ] || continue
		case "${cat_target_version}" in \#*) continue ;; esac
		cat_is_dotted_numeric_version "${cat_target_version}" || {
			echo "CAT migration registry contains an invalid target version: ${cat_target_version}" >&2
			exit 1
		}
		if [ -n "${cat_registry_previous_version}" ]; then
			cat_version_precedes "${cat_registry_previous_version}" "${cat_target_version}" || {
				echo "CAT migration registry versions must be strictly increasing: ${cat_migration_registry}" >&2
				exit 1
			}
		fi
		cat_registry_previous_version="${cat_target_version}"
		case "${cat_script}" in
			''|/*|*//*|./*|../*|*/../*|*/..|.|*/./*|*/.)
				echo "CAT migration registry contains an invalid script path: ${cat_script}" >&2
				exit 1
				;;
		esac
		[ -f "${cat_migrations_directory}/${cat_script}" ] || {
			echo "CAT migration script is missing: ${cat_migrations_directory}/${cat_script}" >&2
			exit 1
		}
		if [ "${cat_first_install}" = false ] && \
			cat_version_precedes "${cat_previous_version}" "${cat_target_version}" && \
			! cat_version_precedes "${CAT_VERSION}" "${cat_target_version}"; then
			CAT_MIGRATION_DATA_DIR="${cat_data_directory}" CAT_MIGRATION_FROM_VERSION="${cat_previous_version}" \
				CAT_MIGRATION_TO_VERSION="${cat_target_version}" sh "${cat_migrations_directory}/${cat_script}"
		fi
	done < "${cat_migration_registry}"
	printf '%s\n' "${CAT_VERSION}" > "${cat_migration_version_file}"
}

# Deletes the temporary directory created while downloading an artifact, if any.
#
# Side effects:
#   Deletes the directory named by CAT_DOWNLOAD_TEMP_DIRECTORY when it exists.
#
# Exit status:
#   Preserves the invoking script's exit status when installed as an exit trap.
cat_delete_download_temp_directory()
{
	if [ -n "${CAT_DOWNLOAD_TEMP_DIRECTORY}" ] && [ -d "${CAT_DOWNLOAD_TEMP_DIRECTORY}" ]; then
		rm -rf "${CAT_DOWNLOAD_TEMP_DIRECTORY}"
	fi
}

# Prints the shared installer usage contract and exits with the command-line usage status.
#
# Arguments:
#   $1 - Path of the invoking script.
#
# Errors:
#   Writes the release-download and extracted-artifact invocation forms to standard error.
#
# Exit status:
#   Exits with status 2.
cat_print_install_usage()
{
	echo "Usage:" >&2
	echo "  $1" >&2
	echo "    Download and install CAT ${CAT_RELEASE_TAG}." >&2
	echo "  $1 ${CAT_RELEASE_TAG}" >&2
	echo "    Download and install CAT ${CAT_RELEASE_TAG}." >&2
	echo "  $1 EXTRACTED_ARTIFACT_DIRECTORY" >&2
	echo "    Install an already extracted CAT artifact directory." >&2
	exit 2
}

# Detects the supported platform identifier used in CAT release archive names.
#
# Output:
#   Writes the release-platform identifier to standard output.
#
# Exit status:
#   Returns zero when the operating system and architecture are supported; otherwise reports them to standard error
#   and returns nonzero.
cat_detect_platform()
{
	cat_os="$(uname -s)"
	cat_architecture="$(uname -m)"
	case "${cat_os}:${cat_architecture}" in
		Linux:x86_64|Linux:amd64) echo "linux-x64" ;;
		Linux:aarch64|Linux:arm64) echo "linux-arm64" ;;
		Darwin:x86_64|Darwin:amd64) echo "macos-x64" ;;
		Darwin:aarch64|Darwin:arm64) echo "macos-arm64" ;;
		MINGW64_NT*:x86_64|MINGW64_NT*:amd64|MSYS_NT*:x86_64|MSYS_NT*:amd64) echo "windows-x64" ;;
		*) echo "Unsupported platform: ${cat_os} ${cat_architecture}" >&2; return 1 ;;
	esac
}

# Resolves an installer input to an extracted artifact directory.
#
# Arguments:
#   $1 - Harness name used in release archive names.
#   $2 - Existing artifact directory or the current release tag.
#
# Environment:
#   Sets CAT_ARTIFACT_DIRECTORY and, when downloading, CAT_DOWNLOAD_TEMP_DIRECTORY.
#
# Side effects:
#   Downloads, verifies, and extracts the requested release when given the release tag. The temporary extraction
#   directory is removed at exit.
#
# Exit status:
#   Exits nonzero after reporting an invalid input, download, checksum, or archive-layout failure.
cat_resolve_artifact_directory()
{
	cat_harness="$1"
	cat_source="$2"
	if [ -d "${cat_source}" ]; then
		CAT_ARTIFACT_DIRECTORY="$(cd "${cat_source}" && pwd)"
		return
	fi
	if [ "${cat_source}" != "${CAT_RELEASE_TAG}" ]; then
		echo "Expected ${CAT_RELEASE_TAG} or an extracted artifact directory: ${cat_source}" >&2
		exit 2
	fi

	cat_platform="$(cat_detect_platform)"
	cat_archive_name="cat-${cat_harness}-v${CAT_VERSION}-${cat_platform}.tar.gz"
	cat_expected_directory="${cat_archive_name%.tar.gz}"
	mkdir -p "${CAT_DOWNLOAD_TEMP_ROOT}"
	CAT_DOWNLOAD_TEMP_DIRECTORY="$(mktemp -d "${CAT_DOWNLOAD_TEMP_ROOT}/cat-${cat_harness}-install.XXXXXX")"
	curl -fsSL "${CAT_RELEASE_DOWNLOAD_URL}/${cat_archive_name}" -o "${CAT_DOWNLOAD_TEMP_DIRECTORY}/${cat_archive_name}"
	curl -fsSL "${CAT_RELEASE_DOWNLOAD_URL}/SHA256SUMS.txt" -o "${CAT_DOWNLOAD_TEMP_DIRECTORY}/SHA256SUMS.txt"
	cat_checksum_line="$(
		awk -v archive="${cat_archive_name}" \
			'$2 == archive || $2 == "*" archive { print; found = 1 } END { if (!found) exit 1 }' \
			"${CAT_DOWNLOAD_TEMP_DIRECTORY}/SHA256SUMS.txt"
	)" || {
		echo "No checksum published for ${cat_archive_name}" >&2
		exit 1
	}
	if [ "$(uname -s)" = "Darwin" ]; then
		printf '%s\n' "${cat_checksum_line}" | (cd "${CAT_DOWNLOAD_TEMP_DIRECTORY}" && shasum -a 256 -c -)
	else
		printf '%s\n' "${cat_checksum_line}" | (cd "${CAT_DOWNLOAD_TEMP_DIRECTORY}" && sha256sum -c -)
	fi
	# GNU tar reads an archive path that starts with a Windows drive, such as C:, as a remote host's name
	(cd "${CAT_DOWNLOAD_TEMP_DIRECTORY}" && tar -xzf "${cat_archive_name}")
	CAT_ARTIFACT_DIRECTORY="${CAT_DOWNLOAD_TEMP_DIRECTORY}/${cat_expected_directory}"
	[ -d "${CAT_ARTIFACT_DIRECTORY}" ] || {
		echo "Archive did not contain the expected directory: ${cat_expected_directory}" >&2
		exit 1
	}
}

# Verifies that an extracted directory is a CAT artifact for one harness.
#
# Arguments:
#   $1 - Extracted artifact directory.
#   $2 - Required harness-specific manifest path relative to that directory.
#   $3 - Harness display name for diagnostics.
#
# Exit status:
#   Exits nonzero after reporting a missing or inconsistent manifest, version file, or plugin-information launcher.
cat_validate_artifact()
{
	cat_artifact_directory="$1"
	cat_manifest_path="$2"
	cat_harness_name="$3"
	[ -f "${cat_artifact_directory}/${cat_manifest_path}" ] || {
		echo "Not a ${cat_harness_name} CAT artifact: missing ${cat_manifest_path}" >&2
		exit 1
	}
	[ "$(tr -d '\r\n' < "${cat_artifact_directory}/client/VERSION")" = "${CAT_VERSION}" ] || {
		echo "CAT artifact version must be ${CAT_VERSION}" >&2
		exit 1
	}
	[ -x "${cat_artifact_directory}/bin/plugin-info" ] || {
		echo "CAT artifact is missing the executable plugin-info launcher" >&2
		exit 1
	}
}

# Removes the Codex agent files recorded as owned by CAT.
#
# Arguments:
#   $1 - Codex home directory.
#   $2 - CAT's persistent Codex data directory.
cat_remove_codex_agents()
{
	cat_codex_home="$1"
	cat_data_directory="$2"
	cat_agent_directory="${cat_codex_home}/agents"
	cat_agent_manifest="${cat_data_directory}/codex-agent-files"
	[ -f "${cat_agent_manifest}" ] || return 0
	while IFS= read -r cat_agent_filename; do
		case "${cat_agent_filename}" in
			cat-*.toml)
				case "${cat_agent_filename}" in */*|*\\*)
					echo "CAT agent ownership manifest contains an invalid filename: ${cat_agent_filename}" >&2
					exit 1
					esac
				rm -f "${cat_agent_directory}/${cat_agent_filename}"
				;;
			*)
				echo "CAT agent ownership manifest contains an invalid filename: ${cat_agent_filename}" >&2
				exit 1
				;;
		esac
	done < "${cat_agent_manifest}"
	rm -f "${cat_agent_manifest}"
}

# Installs the artifact's self-contained Codex custom agents into Codex's supported user-agent directory.
#
# Arguments:
#   $1 - Extracted CAT artifact directory.
#   $2 - Codex home directory.
#   $3 - CAT's persistent Codex data directory.
cat_install_codex_agents()
{
	cat_artifact_directory="$1"
	cat_codex_home="$2"
	cat_data_directory="$3"
	cat_agent_source_directory="${cat_artifact_directory}/agents/codex"
	cat_agent_directory="${cat_codex_home}/agents"
	cat_agent_manifest="${cat_data_directory}/codex-agent-files"
	[ -d "${cat_agent_source_directory}" ] || {
		echo "CAT artifact is missing Codex custom agents: ${cat_agent_source_directory}" >&2
		exit 1
	}
	mkdir -p "${cat_agent_directory}" "${cat_data_directory}"
	cat_new_manifest="${cat_agent_manifest}.new.$$"
	: > "${cat_new_manifest}"
	for cat_agent_source in "${cat_agent_source_directory}"/*.toml; do
		[ -f "${cat_agent_source}" ] || {
			echo "CAT artifact contains no Codex custom-agent definitions: ${cat_agent_source_directory}" >&2
			rm -f "${cat_new_manifest}"
			exit 1
		}
		cat_agent_basename="$(basename "${cat_agent_source}")"
		cat_agent_filename="cat-${cat_agent_basename}"
		if [ -e "${cat_agent_directory}/${cat_agent_filename}" ] && \
			{ [ ! -f "${cat_agent_manifest}" ] || ! grep -Fxq "${cat_agent_filename}" "${cat_agent_manifest}"; }; then
			echo "Refusing to overwrite an unowned Codex agent: ${cat_agent_directory}/${cat_agent_filename}" >&2
			rm -f "${cat_new_manifest}"
			exit 1
		fi
		cp "${cat_agent_source}" "${cat_agent_directory}/${cat_agent_filename}.new"
		printf '%s\n' "${cat_agent_filename}" >> "${cat_new_manifest}"
	done
	cat_remove_codex_agents "${cat_codex_home}" "${cat_data_directory}"
	while IFS= read -r cat_agent_filename; do
		mv "${cat_agent_directory}/${cat_agent_filename}.new" "${cat_agent_directory}/${cat_agent_filename}"
	done < "${cat_new_manifest}"
	mv "${cat_new_manifest}" "${cat_agent_manifest}"
}

# Rejects a marketplace path that is not explicitly the CAT marketplace directory.
#
# Arguments:
#   $1 - Directory reserved for CAT's local plugin marketplace. It must end in /cat-marketplace; otherwise this
#        function refuses the operation before any directory is deleted.
#   $2 - Name of the optional environment variable that overrides this directory, such as
#        CAT_CODEX_MARKETPLACE_ROOT. It is included in the diagnostic so users know which setting to correct.
#
# Exit status:
#   Returns zero only when the directory ends in /cat-marketplace; otherwise explains how to provide a safe override
#   and exits nonzero.
cat_validate_marketplace_root()
{
	cat_marketplace_root="$1"
	cat_marketplace_root_variable="$2"
	case "${cat_marketplace_root}" in
		*/cat-marketplace) ;;
		*)
			echo "CAT marketplace path must end in /cat-marketplace so CAT only replaces its own marketplace:" \
				"${cat_marketplace_root}" >&2
			echo "Set ${cat_marketplace_root_variable} to a path ending in /cat-marketplace." >&2
			exit 1
			;;
	esac
}

# Removes the CAT artifacts of the other harness that a runner skill downloaded.
#
# Arguments:
#   $1 - The user's home directory, whose .cat/bundles directory holds the downloaded artifacts.
cat_remove_downloaded_bundles()
{
	rm -rf "$1/.cat/bundles"
}

# Reject a data path that is not explicitly CAT's Codex data directory.
#
# Arguments:
#   $1 - Directory reserved for CAT's Codex data. It must end in /plugins/data/cat-cat;
#        otherwise this function refuses the operation before any directory is deleted.
#   $2 - Name of the optional environment variable that overrides this directory.
cat_validate_codex_data_directory()
{
	cat_data_directory="$1"
	cat_data_directory_variable="$2"
	case "${cat_data_directory}" in
		*/plugins/data/cat-cat) ;;
		*)
			echo "CAT data path must end in /plugins/data/cat-cat so CAT only removes its own data:" \
				"${cat_data_directory}" >&2
			echo "Set ${cat_data_directory_variable} to a path ending in /plugins/data/cat-cat." >&2
			exit 1
			;;
	esac
}

# Quotes a value for a POSIX shell command line, leaving a value that needs no quotes as it is.
#
# Arguments:
#   $1 - The value to quote.
#
# Output:
#   Writes the quoted value to standard output.
cat_shell_quote()
{
	case "$1" in
		'') printf "''\n" ;;
		*[!A-Za-z0-9_./:@%+=,-]*) printf "'%s'\n" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")" ;;
		*) printf '%s\n' "$1" ;;
	esac
}

# Writes the environment assignments that make a repeated command use the same directories as the running one.
#
# Arguments:
#   $1 - Space-separated names of the environment variables that select the harness's and CAT's directories, such as
#        CODEX_HOME. Call this before the script gives any of them a default value.
#
# Output:
#   Writes one assignment, followed by a space, for each named variable that holds a value and for
#   CAT_BOOTSTRAP_REPOSITORY and CAT_RELEASE_BASE_URL when they hold one; writes nothing when none does.
cat_environment_assignments()
{
	for cat_variable in $1 CAT_BOOTSTRAP_REPOSITORY CAT_RELEASE_BASE_URL; do
		eval "cat_value=\${${cat_variable}:-}"
		[ -z "${cat_value}" ] || printf '%s=%s ' "${cat_variable}" "$(cat_shell_quote "${cat_value}")"
	done
}

# Writes the command that repeats the running installer or uninstaller with the same arguments.
#
# Arguments:
#   $1 - Harness identifier, which the single command passes to --harness.
#   $2 - Environment assignments from cat_environment_assignments.
#   $3 - Git reference, such as v1.0 or HEAD, from which the single command downloads cat.sh.
#   $4 - Absolute path of the running script.
#   $5 - The option that selects the operation in cat.sh: empty to install, or --uninstall.
#   $6... - The running script's arguments, each a release tag, an absolute directory or an option.
#
# Environment:
#   CAT_BOOTSTRAPPED - Nonempty when cat.sh or a bootstrap script started the running script, which may then be a
#                      temporary copy.
#
# Output:
#   Writes the command to standard output: when CAT_BOOTSTRAPPED is set, the single command that downloads cat.sh and
#   pipes it to sh; otherwise the running script with its arguments.
cat_rerun_command()
{
	cat_harness="$1"
	cat_environment="$2"
	cat_reference="$3"
	cat_script="$4"
	cat_operation="$5"
	shift 5
	if [ -n "${CAT_BOOTSTRAPPED:-}" ]; then
		cat_command="curl -fsSL https://raw.githubusercontent.com/${CAT_REPOSITORY}/${cat_reference}/client/common/\
lifecycle/src/main/resources/install/cat.sh | ${cat_environment}sh -s -- --harness ${cat_harness}"
		[ -z "${cat_operation}" ] || cat_command="${cat_command} ${cat_operation}"
	else
		cat_command="${cat_environment}sh $(cat_shell_quote "${cat_script}")"
	fi
	for cat_argument in "$@"; do
		cat_command="${cat_command} $(cat_shell_quote "${cat_argument}")"
	done
	printf '%s\n' "${cat_command}"
}

# Writes how to run a command outside the agent's sandbox, followed by the command.
#
# Arguments:
#   $1 - The command.
#
# Errors:
#   Writes the instructions and the command to standard error.
cat_print_rerun_instructions()
{
	echo "An agent's sandbox usually causes this. Run this command outside the sandbox, such as in a terminal; in" \
		"Claude Code, you can type it at the prompt prefixed with \"! \":" >&2
	echo >&2
	echo "  $1" >&2
}

# Returns whether a file or directory can be written, without changing it.
#
# A directory is writable when a probe file can be created and removed in it; an existing file is writable when it can
# be opened for appending, which writes nothing. Both checks fail on a read-only mount.
#
# Arguments:
#   $1 - The existing file or directory.
#
# Exit status:
#   Returns zero only when the path is writable.
cat_is_writable()
{
	if [ -d "$1" ]; then
		cat_probe="$(mktemp "$1/.cat-write-check.XXXXXX" 2>/dev/null)" && rm -f "${cat_probe}"
	else
		(: >> "$1") 2>/dev/null
	fi
}

# Exits before any change when CAT cannot write a path, explaining why and how to run the command outside the sandbox.
#
# Arguments:
#   $1 - The path that cannot be written.
#   $2 - The command that repeats the operation.
#
# Errors:
#   Names the path, explains that an agent's sandbox usually causes this, and gives the command.
#
# Exit status:
#   Exits with CAT_NOT_WRITABLE_STATUS.
cat_exit_not_writable()
{
	echo "CAT changed nothing because it cannot write $1." >&2
	cat_print_rerun_instructions "$2"
	exit "${CAT_NOT_WRITABLE_STATUS}"
}

# Exits before any change when the operation could not create or change one of the paths.
#
# Arguments:
#   $1 - The command that repeats the operation, which the user can run outside the sandbox.
#   $2... - Each file or directory that the operation creates or changes. An existing file is checked together with
#           its directory, because a program can replace a file by renaming a new one over it. For a path that does
#           not exist, the nearest existing directory above it is checked instead, because the operation creates the
#           path there.
#
# Exit status:
#   Returns zero when every path is writable; otherwise exits as cat_exit_not_writable does, naming the first path
#   that is not.
cat_require_writable()
{
	cat_rerun_command="$1"
	shift
	for cat_path in "$@"; do
		while [ ! -e "${cat_path}" ] && [ "$(dirname -- "${cat_path}")" != "${cat_path}" ]; do
			cat_path="$(dirname -- "${cat_path}")"
		done
		cat_is_writable "${cat_path}" || cat_exit_not_writable "${cat_path}" "${cat_rerun_command}"
		if [ ! -d "${cat_path}" ]; then
			cat_directory="$(dirname -- "${cat_path}")"
			cat_is_writable "${cat_directory}" || cat_exit_not_writable "${cat_directory}" "${cat_rerun_command}"
		fi
	done
}

# Exits before any change when the operation could not delete one of the paths that exist.
#
# Arguments:
#   $1 - The command that repeats the operation, which the user can run outside the sandbox.
#   $2... - Each file or directory that the operation deletes. A path that does not exist is skipped. For one that
#           exists, both it and the directory that holds it must be writable.
#
# Exit status:
#   Returns zero when every existing path can be deleted; otherwise exits as cat_exit_not_writable does, naming the
#   first path that is not writable.
cat_require_removable()
{
	cat_rerun_command="$1"
	shift
	for cat_path in "$@"; do
		[ -e "${cat_path}" ] || continue
		for cat_checked_path in "$(dirname -- "${cat_path}")" "${cat_path}"; do
			cat_is_writable "${cat_checked_path}" || cat_exit_not_writable "${cat_checked_path}" "${cat_rerun_command}"
		done
	done
}

# Records the command that repeats the running operation, before the operation begins changing the harness, so that a
# failure from then on tells the user how to finish it.
#
# Arguments:
#   $1 - The operation, such as "installation" or "removal".
#   $2 - The command that repeats it.
cat_begin_harness_changes()
{
	CAT_RECOVERY_OPERATION="$1"
	CAT_RECOVERY_COMMAND="$2"
}

# Deletes the download directory and, when the operation failed after it began changing the harness, tells the user
# how to finish it.
#
# Side effects:
#   Deletes the directory named by CAT_DOWNLOAD_TEMP_DIRECTORY when it exists.
#
# Errors:
#   After cat_begin_harness_changes and a nonzero exit status, explains that CAT may be incomplete and gives the
#   command that repeats the operation.
#
# Exit status:
#   Preserves the invoking script's exit status when installed as an exit trap.
cat_finish()
{
	cat_exit_status="$?"
	cat_delete_download_temp_directory
	if [ "${cat_exit_status}" -ne 0 ] && [ -n "${CAT_RECOVERY_COMMAND}" ]; then
		echo "CAT's ${CAT_RECOVERY_OPERATION} stopped partway, so CAT may be missing or incomplete. If an error above" \
			"says that a file could not be written, such as \"Read-only file system\", run the command again to finish." >&2
		cat_print_rerun_instructions "${CAT_RECOVERY_COMMAND}"
	fi
	return "${cat_exit_status}"
}
