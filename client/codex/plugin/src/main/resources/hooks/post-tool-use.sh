#!/usr/bin/env sh
# Starts CAT's client/bin/post-tool-use hook from the installed plugin that contains this launcher. Codex runs this hook
# after every shell command, and the hook changes only the result of a command that runs run-part or read-part, so the
# launcher passes every other event over without waiting for the Java runtime to start.
set -eu

event=$(cat)
case ${event} in
	*run-part* | *read-part*) ;;
	*) exit 0 ;;
esac
plugin_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
printf '%s\n' "${event}" | "${plugin_dir}/client/bin/post-tool-use"
