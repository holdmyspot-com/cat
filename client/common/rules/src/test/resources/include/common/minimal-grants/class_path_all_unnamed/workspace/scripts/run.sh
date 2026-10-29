#!/bin/sh
# Builds the tool and prints the length report.
cd "$(dirname "$0")/.." || exit 2
rm -rf out
javac -d out src/com/acme/wordlen/NativeLength.java src/com/acme/wordlen/Main.java || exit 1
java -cp out com.acme.wordlen.Main "$@"
