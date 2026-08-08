# Environment Access

## Design Goals

- Read environment-dependent state through explicit, testable boundaries.

## Guidance

Read environment variables only at a production process boundary, then delegate to the same operation with the resolved
values as explicit parameters. Tests must invoke that operation with values they supply; do not set or read the real
process environment.
