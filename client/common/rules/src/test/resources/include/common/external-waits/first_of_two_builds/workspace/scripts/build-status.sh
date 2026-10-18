#!/bin/sh
# Prints a build's status: "running" until the build finishes, then its result. Usage: build-status.sh <api|web>
state="$(dirname "$0")/../.builds"
mkdir -p "$state"
[ -f "$state/started" ] || date +%s > "$state/started"
elapsed=$(($(date +%s) - $(cat "$state/started")))
case "$1" in
	api)
		if [ "$elapsed" -lt {{failure_after}} ]; then echo running; else echo "failed: {{cause}}"; fi ;;
	web)
		if [ "$elapsed" -lt 1200 ]; then echo running; else echo passed; fi ;;
	*)
		echo "unknown build: $1" >&2
		exit 2 ;;
esac
