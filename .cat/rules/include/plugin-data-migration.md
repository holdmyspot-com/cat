# Plugin Data Migration

## Design Goals

- Confine plugin user-data migration to installation so installed runtime artifacts use one current data contract.

## Guidance

Treat plugin installation as the sole boundary that migrates plugin user data to the current data structure. Keep
compatibility detection and old-format handling inside those installation scripts. Once installation succeeds, plugin
runtime code may assume the current data contract.
