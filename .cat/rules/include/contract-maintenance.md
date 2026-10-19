# Contract Maintenance

## Design Goals

- Keep every maintained system artifact aligned with the current supported contract, without obsolete compatibility
	behavior or explanations misleading a reader or consumer.
- Confine historical data handling and historical records to the boundaries where they are necessary and actionable.

## Guidance

Treat the current supported contract as the only contract implemented and described throughout the system. This applies
to runtime code, public APIs, configuration, scripts, prompts, skills, hooks, launchers, generated help, code comments,
Javadoc, tests, and end-user or maintainer documentation.

When an API, configuration, file format, data structure, command, or documented procedure changes, inventory every
caller and reference to its former contract. Update each to its replacement, then remove obsolete branches, adapters,
aliases, fallbacks, fixtures, comments, and explanatory text. Do not preserve compatibility merely because an artifact
is internal, user-facing, executable, or descriptive.

Do not retain historical prose that describes a prior contract or compares versions in maintained documentation or code
comments. Keep it only in an intentional historical record, such as a changelog, or in a migration script that must
recognize and transform existing user data before current code reads it. Once that migration boundary succeeds, the rest
of the system must assume and describe only the current contract.
