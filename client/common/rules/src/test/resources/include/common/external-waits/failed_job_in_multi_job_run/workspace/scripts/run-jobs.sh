#!/bin/sh
# Prints the state of each job of run 4812, one per line.
state="$(dirname "$0")/../.run"
mkdir -p "$state"
[ -f "$state/started" ] || date +%s > "$state/started"
elapsed=$(($(date +%s) - $(cat "$state/started")))
if [ -f "$state/cancelled" ]; then
	echo "linux: cancelled"
	echo "macos: cancelled"
else
	if [ "$elapsed" -lt 1800 ]; then echo "linux: running"; else echo "linux: passed"; fi
	echo "macos: queued"
fi
if [ "$elapsed" -lt {{failure_after}} ]; then echo "windows: running"; else echo "windows: failed"; fi
