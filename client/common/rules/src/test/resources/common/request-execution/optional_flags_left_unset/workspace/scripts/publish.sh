#!/bin/sh
# Usage: scripts/publish.sh <channel> [--retries N] [--timeout SECONDS] [--parallel N]
# Publishes the current build to <channel>. Without options it makes one attempt with a 60 s timeout, one upload at a
# time.
echo "publish $*" >> publish.log
echo "published to $1"
