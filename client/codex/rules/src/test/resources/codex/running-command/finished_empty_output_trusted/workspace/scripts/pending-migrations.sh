#!/bin/sh
# Lists the database migrations that have not been applied yet, one per line.
cd "$(dirname "$0")/../migrations" || exit 1
for migration in *.sql; do
	grep -qxF "$migration" applied.txt || echo "$migration"
done
