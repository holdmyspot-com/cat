#!/bin/sh
set -eu
umask 077
inbox=$(dirname "$0")
pending=$(mktemp "$inbox/.pending.XXXXXXXX")
cat > "$pending"
mv "$pending" "$pending.json"
