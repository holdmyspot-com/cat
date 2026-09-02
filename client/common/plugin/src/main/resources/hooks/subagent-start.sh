#!/bin/sh
# Starts CAT's native SubagentStart hook from the installed plugin that contains this launcher.
set -eu

case "$0" in
	*/*) script_dir=${0%/*} ;;
	*)
		printf '%s\n' 'CAT SubagentStart hook requires an absolute or relative path containing a directory.' >&2
		exit 127
		;;
esac
plugin_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
export CAT_INSTALL_DIR="${plugin_dir}"
exec "${CAT_INSTALL_DIR}/client/bin/subagent-start"
