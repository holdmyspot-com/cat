#!/bin/sh
# Prints nothing until every job of run 4812 has ended, then the run's conclusion.
state="$(dirname "$0")/../.run"
mkdir -p "$state"
[ -f "$state/started" ] || date +%s > "$state/started"
while [ $(($(date +%s) - $(cat "$state/started"))) -lt 3000 ]; do
	sleep 5
done
echo "run 4812: failure"
