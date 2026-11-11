#!/usr/bin/env bash
# Installs the Linux test tools (bats and shellcheck) with apt. It uses the runner's configured Ubuntu sources first.
# When the update or the install fails, for example because Canonical's servers are unreachable, it points the Ubuntu
# sources at a third-party mirror for the machine's architecture and retries, one mirror after another. Package
# signatures stay verified: only the host in each source's URI changes.
#
# Environment (all optional, for testing): APT_SOURCES_LIST, APT_SOURCES_DIRECTORY, ARCHITECTURE.

set -euo pipefail

PACKAGES=(bats shellcheck)

# Prints the mirrors to try for an architecture, one per line, in order.
# Arguments: the Debian architecture name, such as amd64 or arm64.
# Output: the mirror base URLs. The x64 tree is "ubuntu" and every other architecture uses "ubuntu-ports", which
#   kernel.org does not carry.
mirrors_for_architecture()
{
	case "$1" in
	amd64)
		printf '%s\n' https://mirrors.edge.kernel.org/ubuntu
		;;
	*)
		printf '%s\n' https://mirrors.ocf.berkeley.edu/ubuntu-ports https://mirrors.mit.edu/ubuntu-ports
		;;
	esac
}

# Prints the apt source files that name Ubuntu archives: the legacy list and the deb822 files.
# Output: one path per line; paths that do not exist are omitted.
apt_source_files()
{
	local list="${APT_SOURCES_LIST:-/etc/apt/sources.list}"
	local directory="${APT_SOURCES_DIRECTORY:-/etc/apt/sources.list.d}"
	local file

	for file in "${list}" "${directory}"/*.sources "${directory}"/*.list; do
		[[ -f "${file}" ]] && printf '%s\n' "${file}"
	done
	return 0
}

# Writes a copy of an apt source file in which every canonical Ubuntu archive URI (archive, security, regional, Azure
# and ports hosts under ubuntu.com) and every mirror-list URI (mirror+file:<path>, which the runner images use and
# whose list holds canonical hosts) points at a mirror.
# Arguments: the original file, the file to replace with the rewritten copy, the mirror base URL.
# Side effects: Replaces the target file's contents, with sudo when the target is not writable.
rewrite_source_file()
{
	local original="$1"
	local target="$2"
	local mirror="$3"
	local rewritten
	local -a privileged=()

	[[ -w "${target}" ]] || privileged=(sudo)
	rewritten="$(mktemp "${TMPDIR:-/tmp}/rewritten.XXXXXX")"
	sed -E -e "s#https?://[A-Za-z0-9.-]*ubuntu\.com/ubuntu(-ports)?/?#${mirror}/#g" \
		-e "s#mirror\+file:[^[:space:]]+#${mirror}/#g" "${original}" > "${rewritten}"
	${privileged[@]+"${privileged[@]}"} cp -- "${rewritten}" "${target}"
	rm -f -- "${rewritten}"
}

# Runs the apt update and install. An index that cannot be fetched fails the update, so the caller can fall back at
# once instead of after the install fails.
# Arguments: the number of times apt retries a failed download. Unreachable hosts are retried in vain, so the first
#   attempt passes 0.
# Output: apt's output. Returns apt's failure status.
install_packages()
{
	local retries="$1"
	local -a privileged=()
	local -a options=(-o "Acquire::Retries=${retries}")

	[[ "${EUID}" -eq 0 ]] || privileged=(sudo)
	${privileged[@]+"${privileged[@]}"} apt-get update -o APT::Update::Error-Mode=any "${options[@]}" &&
		${privileged[@]+"${privileged[@]}"} apt-get install --yes "${options[@]}" "${PACKAGES[@]}"
}

# Installs the packages from the configured sources, then from each mirror in turn if that fails.
# Output: a line naming the mirror whenever one is used.
# Returns: nonzero when every source failed.
main()
{
	local architecture="${ARCHITECTURE:-$(dpkg --print-architecture)}"
	local backup_directory
	local file
	local mirror
	local index
	local -a files=()

	if install_packages 0; then
		return 0
	fi
	echo "Installing from the configured Ubuntu sources failed; falling back to a mirror." >&2

	# Every mirror is rewritten from these copies of the original files, not from the previous mirror's rewrite.
	backup_directory="$(mktemp -d "${TMPDIR:-/tmp}/apt-sources.XXXXXX")"
	while IFS= read -r file; do
		cp -- "${file}" "${backup_directory}/${#files[@]}"
		files+=("${file}")
	done < <(apt_source_files)

	while IFS= read -r mirror; do
		echo "Using Ubuntu mirror ${mirror} (architecture ${architecture})." >&2
		for index in "${!files[@]}"; do
			rewrite_source_file "${backup_directory}/${index}" "${files[index]}" "${mirror}"
		done
		if install_packages 3; then
			rm -rf -- "${backup_directory}"
			return 0
		fi
		echo "Installing from ${mirror} failed." >&2
	done < <(mirrors_for_architecture "${architecture}")
	rm -rf -- "${backup_directory}"
	return 1
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
	main "$@"
fi
