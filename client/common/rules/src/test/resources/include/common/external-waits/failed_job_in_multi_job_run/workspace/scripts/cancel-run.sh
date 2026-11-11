#!/bin/sh
# Cancels the jobs of run 4812 that have not ended.
state="$(dirname "$0")/../.run"
mkdir -p "$state"
touch "$state/cancelled"
echo "run 4812: cancelling the queued and running jobs"
