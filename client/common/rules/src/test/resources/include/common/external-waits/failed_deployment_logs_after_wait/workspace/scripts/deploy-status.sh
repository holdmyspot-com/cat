#!/bin/sh
# Prints the staging deployment's status: "running" until the deployment finishes, then its result.
state="$(dirname "$0")/../.deploy"
mkdir -p "$state"
[ -f "$state/started" ] || date +%s > "$state/started"
if [ $(($(date +%s) - $(cat "$state/started"))) -lt {{duration}} ]; then
	echo running
else
	echo "failed: see sh scripts/deploy-logs.sh"
fi
