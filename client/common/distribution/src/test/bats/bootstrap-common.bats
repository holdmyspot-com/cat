#!/usr/bin/env bats

# Tests shared installation inputs and persistent data compatibility.

load bootstrap-helpers

@test "installer preserves supported data independently of the release version" {
	mkdir -p "${TEST_ROOT}/data"
	printf '1.1\n' > "${TEST_ROOT}/data/migration-version"
	printf 'retained data\n' > "${TEST_ROOT}/data/payload"

	run sh -c '. "$1"; cat_migrate_data "$2" "$3"' sh "${INSTALL_DIRECTORY}/common.sh" \
		"${TEST_ROOT}/data" "${INSTALL_DIRECTORY}/migrations"

	[ "${status}" -eq 0 ]
	[ "$(cat "${TEST_ROOT}/data/migration-version")" = "1.1" ]
	[ "$(cat "${TEST_ROOT}/data/payload")" = "retained data" ]
}

@test "installer rejects data newer than its supported data version without changing it" {
	mkdir -p "${TEST_ROOT}/data"
	printf '1.2\n' > "${TEST_ROOT}/data/migration-version"

	run sh -c '. "$1"; cat_migrate_data "$2" "$3"' sh "${INSTALL_DIRECTORY}/common.sh" \
		"${TEST_ROOT}/data" "${INSTALL_DIRECTORY}/migrations"

	[ "${status}" -eq 1 ]
	[[ "${output}" == *"supports data through version 1.1"* ]]
	[ "$(cat "${TEST_ROOT}/data/migration-version")" = "1.2" ]
}

@test "shared installer usage distinguishes download and extracted-artifact inputs" {
	run sh -c '. "$1"; cat_print_install_usage "$2"' sh "${INSTALL_DIRECTORY}/common.sh" cat-install

	[ "${status}" -eq 2 ]
	[[ "${output}" == *"Download and install CAT v1.0."* ]]
	[[ "${output}" == *"EXTRACTED_ARTIFACT_DIRECTORY"* ]]
	[[ "${output}" == *"Install an already extracted CAT artifact directory."* ]]
}
