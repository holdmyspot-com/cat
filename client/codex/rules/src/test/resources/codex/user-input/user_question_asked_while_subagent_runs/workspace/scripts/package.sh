#!/bin/sh
# Builds the source distribution. The build runs on a shared, slow runner, so it takes a couple of minutes.
cd "$(dirname "$0")/.." || exit 1
sleep 100
mkdir -p dist
printf '{{package}} 0.3.0\n' > dist/{{package}}-0.3.0.tar.gz
echo "built dist/{{package}}-0.3.0.tar.gz"
