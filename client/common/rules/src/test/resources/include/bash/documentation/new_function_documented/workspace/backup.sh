#!/usr/bin/env bash
set -euo pipefail

source_dir="$1"
backup_root="${BACKUP_ROOT:-${HOME}/backups}"

mkdir -p "${backup_root}"
cp -r "${source_dir}" "${backup_root}/$(date +%Y%m%dT%H%M%S)"
