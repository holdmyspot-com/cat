#!/usr/bin/env bats
# Tests the Maven wrapper's use of bubblewrap: its private temporary folder where bwrap starts, the caller's temporary
# folder that stays visible inside it, and its unwrapped run with one explanatory line where bwrap is installed but
# cannot start.

# shellcheck disable=SC2030,SC2031 # Bats runs each test in its own process, so a test's TMPDIR never reaches another
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

# Removes the folder and the file that a test created directly below /tmp, outside the test's temporary folder.
# Environment:
#   BELOW_TMP_DIRECTORY - the folder that create_directory_below_tmp created, if any
#   OUTSIDE_FILE - the file that the real-bwrap test created directly in /tmp, if any
teardown()
{
	rm -rf -- "${BELOW_TMP_DIRECTORY:-}" "${OUTSIDE_FILE:-}"
}

# Creates a new empty folder directly below /tmp, where the Claude Code sandbox places a caller's temporary folder.
# Side effects: Creates the folder, which teardown removes.
# Outputs:
#   BELOW_TMP_DIRECTORY - the absolute path of the new folder
create_directory_below_tmp()
{
	BELOW_TMP_DIRECTORY="$(mktemp -d /tmp/mvnw-test-XXXXXX)"
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

# Asserts that every recorded bwrap start line binds no folder other than the root folder.
# Environment:
#   BWRAP_LOG - the file that the bwrap stand-in records its arguments in
assert_no_temporary_folder_bind()
{
	local without_root_bind
	without_root_bind="$(sed 's|--bind / /||g' "${BWRAP_LOG}")"

	[ -s "${BWRAP_LOG}" ]
	[[ "${without_root_bind}" != *--bind* ]]
}

# The wrapper runs bwrap twice, once to see whether it starts and once to run Maven, and both runs must keep the folder.
@test "Maven keeps the caller's temporary folder below /tmp visible in both bwrap starts" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	create_directory_below_tmp
	mkdir "${BELOW_TMP_DIRECTORY}/with space"
	export TMPDIR="${BELOW_TMP_DIRECTORY}/with space"

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	[ "${output}" = "maven started, private tmp=1, arguments=goal" ]
	[ "$(grep -c -- "--tmpfs /tmp --bind ${TMPDIR} ${TMPDIR} --proc /proc" "${BWRAP_LOG}")" -eq 2 ]
}

@test "Maven binds no temporary folder in bwrap when TMPDIR is unset" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	unset TMPDIR

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

@test "Maven binds no temporary folder in bwrap when TMPDIR is outside /tmp" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	export TMPDIR=/var

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

@test "Maven binds no temporary folder in bwrap when TMPDIR is /tmp itself" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	export TMPDIR=/tmp

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

@test "Maven binds no temporary folder in bwrap when TMPDIR names a folder below /tmp that does not exist" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	create_directory_below_tmp
	export TMPDIR="${BELOW_TMP_DIRECTORY}/missing"

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

# Each value below names an existing folder at or above /tmp in another spelling, so binding it would expose more of the
# host's /tmp than the caller's temporary folder.
@test "Maven binds no temporary folder in bwrap when TMPDIR has a doubled slash" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	create_directory_below_tmp
	mkdir "${BELOW_TMP_DIRECTORY}/folder"
	export TMPDIR="${BELOW_TMP_DIRECTORY}//folder"

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

@test "Maven binds no temporary folder in bwrap when TMPDIR is /tmp followed by a slash" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	export TMPDIR=/tmp//

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

@test "Maven binds no temporary folder in bwrap when TMPDIR is /tmp/.." {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	export TMPDIR=/tmp/..

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

@test "Maven binds no temporary folder in bwrap when TMPDIR is /tmp/." {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	export TMPDIR=/tmp/.

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

@test "Maven binds no temporary folder in bwrap when TMPDIR leaves /tmp through a parent-folder component" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	create_directory_below_tmp
	export TMPDIR="${BELOW_TMP_DIRECTORY}/../.."

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

@test "Maven binds no temporary folder in bwrap when TMPDIR names an existing folder below /tmp and ends with a slash" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	create_directory_below_tmp
	export TMPDIR="${BELOW_TMP_DIRECTORY}/"

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

@test "Maven binds no temporary folder in bwrap when TMPDIR contains a current-folder component" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	create_working_bwrap
	create_directory_below_tmp
	mkdir "${BELOW_TMP_DIRECTORY}/folder"
	export TMPDIR="${BELOW_TMP_DIRECTORY}/./folder"

	run --separate-stderr "${WRAPPER}" goal

	[ "${status}" -eq 0 ]
	assert_no_temporary_folder_bind
}

# The wrapper copy and the Maven user home must lie inside the caller's temporary folder, because the private /tmp hides
# everything else below /tmp.
@test "Maven inside the real bwrap can use the caller's temporary folder while the rest of /tmp stays hidden" {
	[ "$(uname)" = Linux ] || skip "the wrapper uses bwrap only on Linux"
	command -v bwrap > /dev/null || skip "bwrap is not installed"
	bwrap --bind / / --tmpfs /tmp --proc /proc --dev /dev -- true > /dev/null 2>&1 ||
		skip "bwrap cannot start in this environment"
	local maven_home
	create_directory_below_tmp
	OUTSIDE_FILE="$(mktemp /tmp/mvnw-outside-XXXXXX)"
	export OUTSIDE_FILE
	cp -R "${BATS_TEST_TMPDIR}/project" "${BATS_TEST_TMPDIR}/home" "${BELOW_TMP_DIRECTORY}/"
	export MAVEN_USER_HOME="${BELOW_TMP_DIRECTORY}/home"
	export TMPDIR="${BELOW_TMP_DIRECTORY}"
	maven_home="$(find "${MAVEN_USER_HOME}/wrapper/dists" -name mvn)"
	# shellcheck disable=SC2016 # The fake Maven expands these variables when it runs
	printf '%s\n' '#!/bin/sh' \
		'[ -d "${TMPDIR}" ] || { echo "TMPDIR is missing"; exit 1; }' \
		'[ ! -e "${OUTSIDE_FILE}" ] || { echo "file outside TMPDIR is visible"; exit 1; }' \
		'echo written > "${TMPDIR}/written-inside"' \
		'echo "maven used TMPDIR"' > "${maven_home}"

	run --separate-stderr "${BELOW_TMP_DIRECTORY}/project/client/mvnw" goal

	[ "${status}" -eq 0 ]
	[ "${output}" = "maven used TMPDIR" ]
	[ "$(cat "${BELOW_TMP_DIRECTORY}/written-inside")" = written ]
	[ -e "${OUTSIDE_FILE}" ]
}
