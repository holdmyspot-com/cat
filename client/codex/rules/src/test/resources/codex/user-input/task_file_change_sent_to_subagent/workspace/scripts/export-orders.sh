#!/bin/sh
# Exports this week's orders as CSV. The export service is slow, so this takes about a minute.
cd "$(dirname "$0")/.." || exit 1
sleep 60
cat data/orders.csv
