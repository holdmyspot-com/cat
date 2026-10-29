#!/bin/sh
# Builds the modules and prints the capacity report.
cd "$(dirname "$0")/.." || exit 2
rm -rf out
javac -d out/sizing src/sizing/module-info.java src/sizing/com/acme/sizing/Capacity.java || exit 1
javac -p out/sizing -d out/app src/app/module-info.java src/app/com/acme/app/Main.java || exit 1
java -p out/sizing:out/app -m com.acme.app/com.acme.app.Main "$@"
