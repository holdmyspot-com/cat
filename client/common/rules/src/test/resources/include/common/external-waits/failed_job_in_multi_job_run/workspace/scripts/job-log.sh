#!/bin/sh
# Prints the log of a job of run 4812. Usage: job-log.sh <linux|macos|windows>
case "$1" in
	windows)
		echo "FAILED {{cause}}" ;;
	linux|macos)
		echo "log not available: the job has not finished" >&2
		exit 1 ;;
	*)
		echo "unknown job: $1" >&2
		exit 2 ;;
esac
