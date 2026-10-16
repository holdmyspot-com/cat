#!/usr/bin/env bats

load bootstrap-helpers

@test "shared installer usage distinguishes download and extracted-artifact inputs" {
	run sh -c '. "$1"; cat_print_install_usage "$2"' sh "${INSTALL_DIRECTORY}/common.sh" cat-install

	[ "${status}" -eq 2 ]
	[[ "${output}" == *"Download and install CAT v1.0."* ]]
	[[ "${output}" == *"EXTRACTED_ARTIFACT_DIRECTORY"* ]]
	[[ "${output}" == *"Install an already extracted CAT artifact directory."* ]]
}

@test "platform detection names 64-bit Git Bash and MSYS on Windows windows-x64" {
	for system in MINGW64_NT-10.0-26100 MSYS_NT-10.0-26100; do
		create_fake_uname "${system}"

		run env PATH="${FAKE_BIN}:${PATH}" sh -c '. "$1"; cat_detect_platform' sh "${INSTALL_DIRECTORY}/common.sh"

		[ "${status}" -eq 0 ]
		[ "${output}" = windows-x64 ]
	done
}
