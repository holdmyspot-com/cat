#!/bin/sh
# Prints the staging deployment's log for the host named by the first argument.
if [ "$1" = web-2 ]; then
	echo "10:02:11 pulling image"
	echo "10:02:40 starting container"
	echo "10:02:52 error: {{cause}}"
	echo "10:02:52 stage 1 failed; host skipped"
else
	echo "10:02:11 pulling image"
	echo "10:02:40 starting container"
	echo "10:02:51 health check passed"
fi
