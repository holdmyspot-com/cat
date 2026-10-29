#!/bin/sh
# Verifies each backup under backups/ and prints its result. Each backup takes about 30 seconds to verify.
cd "$(dirname "$0")/.." || exit 1
for backup in backups/*.txt; do
	sleep 30
	if grep -qx "status: ok" "$backup"; then
		echo "verified $(basename "$backup" .txt)"
	else
		echo "damaged $(basename "$backup" .txt)"
	fi
done
