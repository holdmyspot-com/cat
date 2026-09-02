#!/bin/sh
# Starts CAT's native SessionEnd cleanup hook from the installed Claude plugin.
set -eu

case "$0" in
	*/*) script_dir=${0%/*} ;;
	*)
		printf '%s\n' 'CAT SessionEnd hook requires an absolute or relative path containing a directory.' >&2
		exit 127
		;;
esac
plugin_dir=$(CDPATH='' cd -- "$script_dir/.." && pwd)
export CAT_INSTALL_DIR="${plugin_dir}"
exec "${CAT_INSTALL_DIR}/client/bin/session-end"
