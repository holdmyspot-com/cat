#!/usr/bin/env bats

load bootstrap-helpers

@test "shared installer usage distinguishes download and extracted-artifact inputs" {
	run sh -c '. "$1"; cat_print_install_usage "$2"' sh "${INSTALL_DIRECTORY}/common.sh" cat-install

	[ "${status}" -eq 2 ]
	[[ "${output}" == *"Download and install CAT v1.4."* ]]
	[[ "${output}" == *"EXTRACTED_ARTIFACT_DIRECTORY"* ]]
	[[ "${output}" == *"Install an already extracted CAT artifact directory."* ]]
}
