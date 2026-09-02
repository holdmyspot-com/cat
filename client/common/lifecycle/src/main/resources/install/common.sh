#!/usr/bin/env sh

CAT_VERSION="1.0"
CAT_RELEASE_TAG="${CAT_VERSION}"
CAT_RELEASE_REPOSITORY="${CAT_RELEASE_REPOSITORY:-https://github.com/catsforbots/cat.git}"
CAT_GIT="${CAT_GIT:-git}"
CAT_DOWNLOAD_TEMP_ROOT="${TMPDIR:-/tmp}"
CAT_DOWNLOAD_TEMP_DIRECTORY=""

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
#   Writes the release-tree platform directory to standard output.
#
# Exit status:
#   Returns zero when the operating system and architecture are supported; otherwise reports them to standard error
#   and returns nonzero.
cat_detect_platform()
{
	cat_os="$(uname -s)"
	cat_architecture="$(uname -m)"
	case "${cat_os}:${cat_architecture}" in
		Linux:x86_64|Linux:amd64) echo "linux-x86_64" ;;
		Linux:aarch64|Linux:arm64) echo "linux-arm64" ;;
		Darwin:x86_64|Darwin:amd64) echo "macos-x86_64" ;;
		Darwin:aarch64|Darwin:arm64) echo "macos-arm64" ;;
		MINGW*:*|MSYS*:*|CYGWIN*:*)
			echo "Native Windows is unsupported; install and run CAT inside WSL" >&2
			return 1
			;;
		*) echo "Unsupported platform: ${cat_os} ${cat_architecture}" >&2; return 1 ;;
	esac
}

# Resolves an installer input to a manifest-verified release-tree target directory.
#
# Arguments:
#   $1 - Harness name used in release-tree directory names.
#   $2 - Existing artifact directory or the current immutable release tag.
#
# Environment:
#   Sets CAT_ARTIFACT_DIRECTORY and, when cloning, CAT_DOWNLOAD_TEMP_DIRECTORY.
#
# Side effects:
#   Shallow-clones and verifies the requested release when given the release tag. The temporary checkout directory is
#   removed at exit.
#
# Exit status:
#   Exits nonzero after reporting an invalid input, Git, manifest, or release-tree failure.
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
	command -v "${CAT_GIT}" >/dev/null 2>&1 || {
		echo "CAT requires Git to install a release tag: ${CAT_GIT}" >&2
		exit 1
	}
	mkdir -p "${CAT_DOWNLOAD_TEMP_ROOT}"
	CAT_DOWNLOAD_TEMP_DIRECTORY="$(mktemp -d "${CAT_DOWNLOAD_TEMP_ROOT}/cat-${cat_harness}-install.XXXXXX")"
	cat_repository_directory="${CAT_DOWNLOAD_TEMP_DIRECTORY}/repository"
	"${CAT_GIT}" clone --depth 1 --branch "${CAT_RELEASE_TAG}" "${CAT_RELEASE_REPOSITORY}" \
		"${cat_repository_directory}"
	cat_checked_out_tag="$("${CAT_GIT}" -C "${cat_repository_directory}" describe --exact-match --tags HEAD 2>/dev/null)" || {
		echo "CAT release tree is not checked out at an immutable version tag: ${CAT_RELEASE_TAG}" >&2
		exit 1
	}
	[ "${cat_checked_out_tag}" = "${CAT_RELEASE_TAG}" ] || {
		echo "CAT release tree tag must be ${CAT_RELEASE_TAG}, found ${cat_checked_out_tag}" >&2
		exit 1
	}
	CAT_ARTIFACT_DIRECTORY="${cat_repository_directory}/${cat_harness}/${cat_platform}"
	[ -d "${CAT_ARTIFACT_DIRECTORY}" ] || {
		echo "CAT release tag does not support ${cat_harness}/${cat_platform}: ${CAT_RELEASE_TAG}" >&2
		exit 1
	}
	cat_verify_release_manifest "${cat_repository_directory}" "${cat_harness}" "${cat_platform}"
}

# Verifies the target files named by an immutable release-tree manifest.
#
# Arguments:
#   $1 - The root of the checked-out release tree.
#   $2 - The active harness name.
#   $3 - The exact selected platform directory.
#
# Exit status:
#   Exits nonzero after reporting a malformed manifest, unsafe path, unlisted file, size mismatch, or checksum mismatch.
cat_verify_release_manifest()
{
	cat_release_root="$1"
	cat_harness="$2"
	cat_platform="$3"
	cat_manifest="${cat_release_root}/release-manifest.json"
	[ -f "${cat_manifest}" ] || {
		echo "CAT release tag does not contain release-manifest.json: ${CAT_RELEASE_TAG}" >&2
		exit 1
	}
	grep -Fq '"schema_version" : "cat-release-v1"' "${cat_manifest}" || {
		echo "CAT release manifest has an unsupported schema" >&2
		exit 1
	}
	grep -Fq "\"version\" : \"${CAT_VERSION}\"" "${cat_manifest}" || {
		echo "CAT release manifest version must be ${CAT_VERSION}" >&2
		exit 1
	}
	cat_manifest_entries="${CAT_DOWNLOAD_TEMP_DIRECTORY}/manifest-entries.tsv"
	awk '
		/"path"[[:space:]]*:/ {
			path = $0; sub(/^.*"path"[[:space:]]*:[[:space:]]*"/, "", path); sub(/".*$/, "", path)
		}
		/"sha256"[[:space:]]*:/ {
			hash = $0; sub(/^.*"sha256"[[:space:]]*:[[:space:]]*"/, "", hash); sub(/".*$/, "", hash)
		}
		/"size"[[:space:]]*:/ {
			size = $0; sub(/^.*"size"[[:space:]]*:[[:space:]]*/, "", size); sub(/[^0-9].*$/, "", size)
			if (path == "" || hash !~ /^[0-9a-f]{64}$/ || size !~ /^[0-9]+$/) exit 1
			print path "\t" hash "\t" size; path = ""; hash = ""; size = ""
		}
		END { if (NR == 0 || path != "" || hash != "" || size != "") exit 1 }
	' "${cat_manifest}" > "${cat_manifest_entries}" || {
		echo "CAT release manifest is malformed" >&2
		exit 1
	}
	[ -s "${cat_manifest_entries}" ] || {
		echo "CAT release manifest contains no file entries" >&2
		exit 1
	}
	while IFS="$(printf '\t')" read -r cat_manifest_path cat_manifest_sha256 cat_manifest_size; do
		case "${cat_manifest_path}" in
			LICENSE.md|README.md|codex/*|claude/*) ;;
			*) echo "CAT release manifest contains an unsupported path: ${cat_manifest_path}" >&2; exit 1 ;;
		esac
		case "${cat_manifest_path}" in /*|*'..'*|*'//'*)
			echo "CAT release manifest contains an unsafe target path: ${cat_manifest_path}" >&2
			exit 1
			;;
		esac
		cat_manifest_file="${cat_release_root}/${cat_manifest_path}"
		if [ ! -f "${cat_manifest_file}" ] || [ -L "${cat_manifest_file}" ]; then
			echo "CAT release manifest file is missing or unsafe: ${cat_manifest_path}" >&2
			exit 1
		fi
		[ "$(wc -c < "${cat_manifest_file}" | tr -d '[:space:]')" = "${cat_manifest_size}" ] || {
			echo "CAT release manifest size mismatch: ${cat_manifest_path}" >&2
			exit 1
		}
		if command -v sha256sum >/dev/null 2>&1; then
			cat_actual_sha256="$(sha256sum "${cat_manifest_file}" | awk '{ print $1 }')"
		else
			cat_actual_sha256="$(shasum -a 256 "${cat_manifest_file}" | awk '{ print $1 }')"
		fi
		[ "${cat_actual_sha256}" = "${cat_manifest_sha256}" ] || {
			echo "CAT release manifest checksum mismatch: ${cat_manifest_path}" >&2
			exit 1
		}
	done < "${cat_manifest_entries}"
	find "${cat_release_root}" -type l -print -quit | grep -q . && {
		echo "CAT release tree contains a symbolic link" >&2
		exit 1
	}
	find "${cat_release_root}" -type f ! -name release-manifest.json -print | while IFS= read -r cat_actual_file; do
		cat_actual_path="${cat_actual_file#"${cat_release_root}"/}"
		cut -f 1 "${cat_manifest_entries}" | grep -Fqx "${cat_actual_path}" || {
			echo "CAT release target contains an unlisted file: ${cat_actual_path}" >&2
			exit 1
		}
	done
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
	[ -x "${cat_artifact_directory}/client/bin/plugin-info" ] || {
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
	cat_common_agent_directory="${cat_artifact_directory}/agents/common"
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
		cat_common_agent="$(sed -n 's|^<!-- cat:include ../common/\([A-Za-z0-9_.-]*\) -->$|\1|p' "${cat_agent_source}")"
		case "${cat_common_agent}" in
			*'
'*)
				echo "CAT custom agent must contain exactly one common-agent include: ${cat_agent_source}" >&2
				rm -f "${cat_new_manifest}"
				exit 1
				;;
		esac
		if [ -e "${cat_agent_directory}/${cat_agent_filename}" ] && \
			{ [ ! -f "${cat_agent_manifest}" ] || ! grep -Fxq "${cat_agent_filename}" "${cat_agent_manifest}"; }; then
			echo "Refusing to overwrite an unowned Codex agent: ${cat_agent_directory}/${cat_agent_filename}" >&2
			rm -f "${cat_new_manifest}"
			exit 1
		fi
		if [ -z "${cat_common_agent}" ]; then
			cp "${cat_agent_source}" "${cat_agent_directory}/${cat_agent_filename}.new"
		else
			cat_common_agent_source="${cat_common_agent_directory}/${cat_common_agent}"
			[ -f "${cat_common_agent_source}" ] || {
				echo "CAT custom agent references a missing common agent: ${cat_common_agent_source}" >&2
				rm -f "${cat_new_manifest}"
				exit 1
			}
			awk -v agent_include="${cat_common_agent}" -v common="${cat_common_agent_source}" '
				$0 == "<!-- cat:include ../common/" agent_include " -->" {
					while ((getline line < common) > 0) print line
					close(common)
					next
				}
				{ print }
			' "${cat_agent_source}" > "${cat_agent_directory}/${cat_agent_filename}.new"
		fi
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
