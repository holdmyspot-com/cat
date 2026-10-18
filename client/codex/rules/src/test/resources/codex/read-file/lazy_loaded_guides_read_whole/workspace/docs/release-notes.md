# Release Notes

## Release {{release}}

- Improved the {{improved}} so repeated runs finish sooner on large inputs.
- Corrected a message in the {{corrected}} that named the wrong option.

## Migration policy

Only release 4.173 requires a caller migration: the exporter now stops with an error unless each call passes an explicit destination; callers relying on the previous default directory must add one.

