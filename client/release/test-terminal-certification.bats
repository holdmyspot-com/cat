#!/usr/bin/env bats

# Verifies terminal certification keeps harness evidence available after a failed live-harness attempt.

@test "terminal certification retains the failed harness evidence directory" {
	bin="$BATS_TEST_TMPDIR/bin"
	personal="$BATS_TEST_TMPDIR/personal"
	commercial="$BATS_TEST_TMPDIR/commercial"
	case "$(uname -s):$(uname -m)" in
		Linux:x86_64|Linux:amd64) platform=linux-x86_64 ;;
		Linux:aarch64|Linux:arm64) platform=linux-arm64 ;;
		Darwin:x86_64|Darwin:amd64) platform=macos-x86_64 ;;
		Darwin:aarch64|Darwin:arm64) platform=macos-arm64 ;;
		*) skip "Unsupported terminal-certification test platform" ;;
	esac
	mkdir -p "$bin" "$personal" "$commercial/codex/$platform/client/bin" \
		"$commercial/claude/$platform/client/bin"
	printf '%s\n' '#!/usr/bin/env sh' 'exit 0' > "$bin/codex"
	printf '%s\n' '#!/usr/bin/env sh' 'exit 0' > "$bin/claude"
	chmod +x "$bin/codex" "$bin/claude"
	printf '%s\n' '#!/usr/bin/env sh' 'set -eu' 'output=' 'receipt=' \
		'while [ "$#" -gt 0 ]; do case "$1" in --output) output=$2; shift 2 ;; --receipt) receipt=$2; shift 2 ;; *) shift ;; esac; done' \
		'printf "%s\\n" "{\"type\":\"turn-completed\",\"assistant_response\":\"READY\"}" > "$output"' \
		'printf "%s" "receipt" > "$receipt"' > "$commercial/codex/$platform/client/bin/codex-runner"
	printf '%s\n' '#!/usr/bin/env sh' 'set -eu' 'run_root=' \
		'while [ "$#" -gt 0 ]; do case "$1" in --run-root) run_root=$2; shift 2 ;; *) shift ;; esac; done' \
		'mkdir -p "$run_root/claude/failed/artifacts"' \
		'printf "%s" "Claude provider request failed with HTTP 503" > "$run_root/claude/failed/artifacts/turn-1.stderr"' \
		'exit 1' \
		> "$commercial/claude/$platform/client/bin/claude-runner"
	chmod +x "$commercial/codex/$platform/client/bin/codex-runner" \
		"$commercial/claude/$platform/client/bin/claude-runner"

	run env PATH="$bin:$PATH" TMPDIR="$BATS_TEST_TMPDIR" "$BATS_TEST_DIRNAME/test-terminal-certification.sh" \
		"$personal" "$commercial" 1.0

	[ "$status" -eq 77 ]
	retained="$(printf '%s\n' "$output" | sed -n 's/^Terminal certification evidence retained at //p')"
	[ -n "$retained" ]
	[ -f "$retained/claude-runs/claude/failed/artifacts/turn-1.stderr" ]
}
