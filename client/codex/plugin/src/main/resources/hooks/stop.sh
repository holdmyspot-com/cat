#!/usr/bin/env sh
# Runs CAT's Codex Stop hook from the installed plugin that contains this launcher.
set -eu

plugin_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -z "${CAT_INSTALL_DIR+x}" ]; then
	export CAT_INSTALL_DIR="${plugin_dir}"
fi
exec "${plugin_dir}/client/bin/usage-stop"
