#!/bin/sh
# Starts and reports the CI runs of this repository. Run it without arguments for its commands.
exec python3 "$(dirname "$0")/ci.py" "$@"
