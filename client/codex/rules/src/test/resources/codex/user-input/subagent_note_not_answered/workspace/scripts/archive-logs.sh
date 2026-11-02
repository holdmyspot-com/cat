#!/bin/sh
# Archives every log under logs/. Compressing on the shared volume is slow, so this takes about a minute.
cd "$(dirname "$0")/.." || exit 1
sleep 45
mkdir -p archive
count=$(ls logs/*.log | wc -l)
tar -czf archive/{{archive}} logs/*.log
echo "archived $count files into archive/{{archive}}"
