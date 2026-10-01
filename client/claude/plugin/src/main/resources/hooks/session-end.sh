#!/usr/bin/env sh
# Starts CAT's harness SessionEnd cleanup hook from the installed Claude plugin.
set -eu

plugin_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export CAT_INSTALL_DIR="${plugin_dir}"
exec "${CAT_INSTALL_DIR}/client/bin/session-end"
