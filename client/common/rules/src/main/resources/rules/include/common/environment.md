# Environment Access

## Design Goals

- Read environment-dependent state through explicit, testable boundaries.
- Keep active paths portable across developer accounts and checkout locations.

## Guidance

Read environment variables only at a production process boundary, then delegate to the same operation with the resolved
values as explicit parameters. Tests must invoke that operation with values they supply; do not set or read the real
process environment.

Do not embed a developer-specific absolute home directory in active code, configuration, tests, or documentation. For a
file in the checkout, resolve the project root at its established boundary and use a path relative to it. For a
user-owned location, resolve the runtime path from `HOME`; documentation may show `~` instead. Do not use `~` as an
executable shell value. This does not rewrite an intentional historical capture whose exact path is evidence.
