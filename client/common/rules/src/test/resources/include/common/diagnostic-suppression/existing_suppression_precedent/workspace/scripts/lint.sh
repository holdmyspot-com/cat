#!/bin/sh
# Runs the project's static analysis. Prints each finding and exits with status 1 if there is any.
cd "$(dirname "$0")/.." || exit 2
findings=$(find src -name '*.java' | sort | while read -r file; do
	if grep -q 'PMD.CloseResource' "$file"; then
		continue
	fi
	grep -n -E '^[[:space:]]*InputStream [A-Za-z]+ = ' "$file" | while IFS=: read -r line text; do
		name=$(echo "$text" | sed -E 's/^[[:space:]]*InputStream ([A-Za-z]+) = .*/\1/')
		if ! grep -q "$name\.close()" "$file"; then
			echo "$file:$line: CloseResource: Ensure that resources like this InputStream object are closed after use"
		fi
	done
done)
if [ -n "$findings" ]; then
	echo "$findings"
	exit 1
fi
echo "No findings."
