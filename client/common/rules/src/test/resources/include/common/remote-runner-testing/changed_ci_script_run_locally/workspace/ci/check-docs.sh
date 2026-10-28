#!/usr/bin/env sh
# Fails when a Markdown file under docs/ repeats a level-two heading.
set -eu
status=0
for file in docs/*.md; do
	duplicates=$(grep '^## ' "$file" | sort | uniq -d)
	if [ -n "$duplicates" ]; then
		printf '%s repeats: %s\n' "$file" "$duplicates"
		status=1
	fi
done
exit "$status"
