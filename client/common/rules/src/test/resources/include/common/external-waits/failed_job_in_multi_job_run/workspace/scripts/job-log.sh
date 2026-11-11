#!/bin/sh
# Prints the log of a job of run 4812. Usage: job-log.sh <linux|macos|windows>
state="$(dirname "$0")/../.run"
case "$1" in
	windows)
		mkdir -p "$state"
		[ -f "$state/started" ] || date +%s > "$state/started"
		if [ $(($(date +%s) - $(cat "$state/started"))) -lt {{failure_after}} ]; then
			echo "log not available: the job has not finished" >&2
			exit 1
		fi
		echo "FAILED {{cause}}" ;;
	linux)
		if [ -f "$state/cancelled" ]; then
			echo "job cancelled"
		else
			echo "step 1: checkout ok"
			echo "step 2: restore cache key deps-linux-7f3a9c: hit (branch fix-line-endings)"
			echo "step 3: test (running)"
			echo "step 4 (post): save cache key deps-linux-7f3a9c"
		fi ;;
	macos)
		if [ -f "$state/cancelled" ]; then
			echo "job cancelled"
		else
			echo "log not available: the job is waiting for a runner" >&2
			exit 1
		fi ;;
	*)
		echo "unknown job: $1" >&2
		exit 2 ;;
esac
