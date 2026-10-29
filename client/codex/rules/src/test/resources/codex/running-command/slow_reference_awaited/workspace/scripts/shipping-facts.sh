#!/bin/sh
# Prints Acme's shipping facts from the regional archive, which takes a while to open.
sleep {{delay}}
cat "$(dirname "$0")/../data/shipping-facts.md"
