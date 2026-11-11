#!/usr/bin/env bats
# Tests how the workflows' Linux test-tool installer picks Ubuntu mirrors and rewrites apt sources. The tests call the
# script's functions on sample files; they never run apt or reach the network.

bats_require_minimum_version 1.5.0

setup()
{
	SCRIPT="${BATS_TEST_DIRNAME}/../../../../../../.github/scripts/install-linux-test-tools.sh"
	# shellcheck source=/dev/null
	source "${SCRIPT}"
	SOURCES_FILE="${BATS_TEST_TMPDIR}/ubuntu.sources"
	cat > "${SOURCES_FILE}" <<'SOURCES'
Types: deb
URIs: http://azure.archive.ubuntu.com/ubuntu/
Suites: resolute resolute-updates
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: http://security.ubuntu.com/ubuntu/
Suites: resolute-security
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
SOURCES
}

@test "x64 uses the kernel.org mirror only" {
	run mirrors_for_architecture amd64
	[ "${status}" -eq 0 ]
	[ "${output}" = "https://mirrors.edge.kernel.org/ubuntu" ]
}

@test "arm64 uses the Berkeley ports mirror, then the MIT one" {
	run mirrors_for_architecture arm64
	[ "${status}" -eq 0 ]
	[ "${lines[0]}" = "https://mirrors.ocf.berkeley.edu/ubuntu-ports" ]
	[ "${lines[1]}" = "https://mirrors.mit.edu/ubuntu-ports" ]
	[ "${#lines[@]}" -eq 2 ]
}

@test "a deb822 file has its archive and security URIs rewritten and keeps its signature settings" {
	rewrite_source_file "${SOURCES_FILE}" "${SOURCES_FILE}" https://mirrors.edge.kernel.org/ubuntu
	[ "$(grep -c '^URIs: https://mirrors.edge.kernel.org/ubuntu/$' "${SOURCES_FILE}")" -eq 2 ]
	run ! grep -q 'ubuntu\.com' "${SOURCES_FILE}"
	[ "$(grep -c '^Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg$' "${SOURCES_FILE}")" -eq 2 ]
	run ! grep -Eq 'trusted|allow-insecure' "${SOURCES_FILE}"
}

@test "a legacy list on the ports tree is rewritten to the ports mirror" {
	local list="${BATS_TEST_TMPDIR}/sources.list"
	printf '%s\n' 'deb http://ports.ubuntu.com/ubuntu-ports resolute main' \
		'deb [arch=arm64] http://security.ubuntu.com/ubuntu resolute-security main' > "${list}"
	rewrite_source_file "${list}" "${list}" https://mirrors.mit.edu/ubuntu-ports
	grep -qx 'deb https://mirrors.mit.edu/ubuntu-ports/ resolute main' "${list}"
	grep -qx 'deb \[arch=arm64\] https://mirrors.mit.edu/ubuntu-ports/ resolute-security main' "${list}"
}

@test "only existing source files are listed" {
	local directory="${BATS_TEST_TMPDIR}/sources.list.d"
	mkdir "${directory}"
	cp "${SOURCES_FILE}" "${directory}/ubuntu.sources"
	APT_SOURCES_LIST="${BATS_TEST_TMPDIR}/missing.list" APT_SOURCES_DIRECTORY="${directory}" run apt_source_files
	[ "${status}" -eq 0 ]
	[ "${output}" = "${directory}/ubuntu.sources" ]
}
