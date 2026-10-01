#!/usr/bin/env sh
# Starts CAT's harness SubagentStart hook from the installed plugin that contains this launcher.
set -eu

plugin_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export CAT_INSTALL_DIR="${plugin_dir}"
exec "${CAT_INSTALL_DIR}/client/bin/subagent-start"
