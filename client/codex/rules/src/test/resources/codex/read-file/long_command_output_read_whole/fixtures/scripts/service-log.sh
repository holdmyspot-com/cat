#!/bin/sh
# Prints the payment service's log, oldest entry first.
cat "$(dirname "$0")/../logs/service.log"
