#!/usr/bin/env bats

# Verifies terminal certification keeps harness evidence available after a failed live-harness attempt.

@test "terminal certification retains the failed harness evidence directory" {
	bin="$BATS_TEST_TMPDIR/bin"
	personal="$BATS_TEST_TMPDIR/personal"
	commercial="$BATS_TEST_TMPDIR/commercial"
	mkdir -p "$bin" "$personal" "$commercial/codex/linux-x86_64/client/bin" \
		"$commercial/claude/linux-x86_64/client/bin"
	printf '%s\n' '#!/usr/bin/env sh' 'exit 0' > "$bin/codex"
	printf '%s\n' '#!/usr/bin/env sh' 'exit 0' > "$bin/claude"
	chmod +x "$bin/codex" "$bin/claude"
	printf '%s\n' '#!/usr/bin/env sh' 'set -eu' 'output=' 'receipt=' \
		'while [ "$#" -gt 0 ]; do case "$1" in --output) output=$2; shift 2 ;; --receipt) receipt=$2; shift 2 ;; *) shift ;; esac; done' \
		'printf "%s\\n" "{\"type\":\"turn-completed\",\"assistant_response\":\"READY\"}" > "$output"' \
		'printf "%s" "receipt" > "$receipt"' > "$commercial/codex/linux-x86_64/client/bin/codex-runner"
	printf '%s\n' '#!/usr/bin/env sh' 'set -eu' 'run_root=' \
		'while [ "$#" -gt 0 ]; do case "$1" in --run-root) run_root=$2; shift 2 ;; *) shift ;; esac; done' \
		'mkdir -p "$run_root/claude/failed/artifacts"' \
		'printf "%s" "Claude provider request failed with HTTP 503" > "$run_root/claude/failed/artifacts/turn-1.stderr"' \
		'exit 1' \
		> "$commercial/claude/linux-x86_64/client/bin/claude-runner"
	chmod +x "$commercial/codex/linux-x86_64/client/bin/codex-runner" \
		"$commercial/claude/linux-x86_64/client/bin/claude-runner"

	run env PATH="$bin:$PATH" TMPDIR="$BATS_TEST_TMPDIR" "$BATS_TEST_DIRNAME/test-terminal-certification.sh" \
		"$personal" "$commercial" 1.0

	[ "$status" -eq 77 ]
	retained="$(printf '%s\n' "$output" | sed -n 's/^Terminal certification evidence retained at //p')"
	[ -n "$retained" ]
	[ -f "$retained/claude-runs/claude/failed/artifacts/turn-1.stderr" ]
}
