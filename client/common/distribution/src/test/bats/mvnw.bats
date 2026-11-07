#!/usr/bin/env bats
# Tests the Maven wrapper's use of bubblewrap: its private temporary folder where bwrap starts, and its unwrapped run
# with one explanatory line where bwrap is installed but cannot start.

bats_require_minimum_version 1.5.0

# Prints the folder name that the wrapper gives a Maven distribution: the hash of its URL, as Java's String.hashCode
# computes it, in hexadecimal.
# Arguments: the distribution URL.
# Output: the hash.
maven_home_hash()
{
	local url="$1"
	local hash=0
	local character

	while [ -n "${url}" ]; do
		character="${url%"${url#?}"}"
		hash=$(((hash * 31 + $(LC_CTYPE=C printf %d "'${character}")) % 4294967296))
		url="${url#?}"
	done
	printf %x "${hash}"
}

# Copies the Maven wrapper beside an existing fake Maven installation that prints how it was started.
# Side effects: Creates the wrapper copy and FAKE_BIN beneath the test's temporary folder, and puts FAKE_BIN first on PATH.
# Outputs:
#   WRAPPER - the copy of client/mvnw
setup()
{
	local wrapper_directory="${BATS_TEST_TMPDIR}/project/client"
	local maven_home

	mkdir -p "${wrapper_directory}/.mvn/wrapper" "${BATS_TEST_TMPDIR}/home" "${BATS_TEST_TMPDIR}/bin"
	printf '%s\n' 'distributionUrl=https://example.invalid/apache-maven-9.9.9-bin.zip' \
		> "${wrapper_directory}/.mvn/wrapper/maven-wrapper.properties"
	cp "${BATS_TEST_DIRNAME}/../../../../../mvnw" "${wrapper_directory}/mvnw"
	WRAPPER="${wrapper_directory}/mvnw"
	export FAKE_BIN="${BATS_TEST_TMPDIR}/bin"
	export MAVEN_USER_HOME="${BATS_TEST_TMPDIR}/home"
	unset CAT_MAVEN_PRIVATE_TMP
	# The wrapper starts Maven from MAVEN_HOME when that folder exists, so no download happens
	maven_home="$(maven_home_hash https://example.invalid/apache-maven-9.9.9-bin.zip)"
	mkdir -p "${MAVEN_USER_HOME}/wrapper/dists/apache-maven-9.9.9/${maven_home}/bin"
	# shellcheck disable=SC2016 # The fake Maven expands these variables when it runs
	printf '%s\n' '#!/bin/sh' 'echo "maven started, private tmp=${CAT_MAVEN_PRIVATE_TMP-unset}, arguments=$*"' \
		> "${MAVEN_USER_HOME}/wrapper/dists/apache-maven-9.9.9/${maven_home}/bin/mvn"
	chmod +x "${MAVEN_USER_HOME}/wrapper/dists/apache-maven-9.9.9/${maven_home}/bin/mvn"
	PATH="${FAKE_BIN}:${PATH}"
}

# Installs a bwrap stand-in that fails to start, as on a host that denies unprivileged user namespaces.
create_failing_bwrap()
{
	printf '%s\n' '#!/bin/sh' 'echo "bwrap: setting up uid map: Permission denied" >&2' 'exit 1' > "${FAKE_BIN}/bwrap"
	chmod +x "${FAKE_BIN}/bwrap"
}

# Installs a bwrap stand-in that starts: it applies --setenv options and runs the command after "--", and records that it
# ran in BWRAP_LOG.
create_working_bwrap()
{
	export BWRAP_LOG="${BATS_TEST_TMPDIR}/bwrap.log"
	cat > "${FAKE_BIN}/bwrap" <<'SCRIPT'
#!/bin/sh
echo "bwrap $*" >> "${BWRAP_LOG}"
while [ "$1" != -- ]; do
	if [ "$1" = --setenv ]; then
		export "$2=$3"
		shift 2
	fi
	shift
done
shift
exec "$@"
SCRIPT
	chmod +x "${FAKE_BIN}/bwrap"
}

@test "Maven runs unwrapped with one line saying why when bwrap cannot start" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_failing_bwrap

	run --separate-stderr "${WRAPPER}" -v goal

	[ "${status}" -eq 0 ]
	[ "${output}" = "maven started, private tmp=1, arguments=-v goal" ]
	# shellcheck disable=SC2154 # bats defines stderr_lines for run --separate-stderr
	[ "${#stderr_lines[@]}" -eq 1 ]
	[[ "${stderr}" == *"without bwrap"*"setting up uid map: Permission denied" ]]
}

@test "Maven runs inside bwrap with the private temporary folder marker when bwrap starts" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	[ "${output}" = "maven started, private tmp=1, arguments=goal" ]
	[ -z "${stderr}" ]
	[ "$(grep -c -- '--tmpfs /tmp' "${BWRAP_LOG}")" -ge 1 ]
}
