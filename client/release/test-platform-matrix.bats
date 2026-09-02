#!/usr/bin/env bats

# Verifies the release platform matrix accepts native CI receipts for the shared Linux and WSL artifact.

setup() {
	case "$(uname -s):$(uname -m)" in
		Linux:x86_64|Linux:amd64) platform=linux-x86_64 ;;
		Linux:aarch64|Linux:arm64) platform=linux-arm64 ;;
		Darwin:x86_64|Darwin:amd64) platform=macos-x86_64 ;;
		Darwin:aarch64|Darwin:arm64) platform=macos-arm64 ;;
		*) skip "Unsupported platform-matrix test platform" ;;
	esac
	personal="$BATS_TEST_TMPDIR/personal"
	commercial="$BATS_TEST_TMPDIR/commercial"
	receipts="$BATS_TEST_TMPDIR/receipts"
	for edition_tree in "$personal" "$commercial"; do
		for harness in codex claude; do
			runner="$edition_tree/$harness/$platform/client/bin/$harness-runner"
			mkdir -p "$(dirname "$runner")"
			printf '%s\n' '#!/usr/bin/env sh' 'exit 0' > "$runner"
			chmod +x "$runner"
		done
	done
	mkdir -p "$receipts"
	for receipt_platform in linux-x86_64 linux-arm64 macos-x86_64 macos-arm64; do
		cat > "$receipts/$receipt_platform.json" <<EOF
{
  "schema_version": "cat-platform-receipt-v1",
  "version": "1.0",
  "platform": "$receipt_platform",
  "execution_environment": "native",
  "created_at": "2026-09-02T00:00:00Z"
}
EOF
	done
}

@test "platform matrix accepts native Linux evidence without a separate WSL receipt" {
	run env CAT_PLATFORM_RECEIPT_DIRECTORY="$receipts" "$BATS_TEST_DIRNAME/test-platform-matrix.sh" \
		"$personal" "$commercial" 1.0

	[ "$status" -eq 0 ]
	[[ "$output" == *"Platform matrix passed: version=1.0"* ]]
}

@test "platform matrix rejects the retired WSL receipt command" {
	run "$BATS_TEST_DIRNAME/test-platform-matrix.sh" --record-wsl "$personal" "$commercial" 1.0 \
		"$BATS_TEST_TMPDIR/wsl-receipt.json"

	[ "$status" -eq 2 ]
	[[ "$output" == *"Usage:"* ]]
}
