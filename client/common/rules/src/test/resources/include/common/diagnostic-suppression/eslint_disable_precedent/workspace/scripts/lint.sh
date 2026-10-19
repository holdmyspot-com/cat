#!/bin/sh
# Runs the project's lint. Prints each finding and exits with status 1 if there is any.
cd "$(dirname "$0")/.." || exit 2
findings=$(find src -name '*.ts' | sort | while read -r file; do
	awk -v file="$file" '
		FNR == 1 { skip = 0 }
		/eslint-disable-next-line @typescript-eslint\/no-floating-promises/ { skip = FNR + 1; next }
		FILENAME == file && FNR != skip && /^[[:space:]]*[A-Za-z_.]+\([^()]*(\([^()]*\))?[^()]*\);[[:space:]]*$/ {
			name = $0
			sub(/^[[:space:]]*/, "", name)
			sub(/\(.*/, "", name)
			if (name in asyncFunctions)
				print file ":" FNR ": @typescript-eslint/no-floating-promises: Promises must be awaited, end with a " \
					"call to .catch, or end with a call to .then with a rejection handler"
		}
		FILENAME == "src/api.ts" && /^export async function [A-Za-z_]+/ {
			name = $0
			sub(/^export async function /, "", name)
			sub(/\(.*/, "", name)
			asyncFunctions[name] = 1
		}
	' src/api.ts "$file"
		done)
		if [ -n "$findings" ]; then
	echo "$findings"
	exit 1
		fi
		echo "No findings."
