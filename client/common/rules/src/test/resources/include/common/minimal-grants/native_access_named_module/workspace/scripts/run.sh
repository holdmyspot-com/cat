#!/bin/sh
# Builds the modules and prints the length report.
cd "$(dirname "$0")/.." || exit 2
rm -rf out
javac -d out/digest src/digest/module-info.java src/digest/com/acme/digest/NativeLength.java || exit 1
javac -p out/digest -d out/app src/app/module-info.java src/app/com/acme/app/Main.java || exit 1
java -p out/digest:out/app -m com.acme.app/com.acme.app.Main "$@"
