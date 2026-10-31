# Packaged Plugin Content

## Design Goals

- Keep every path that a published plugin artifact references resolvable within that artifact.
- Keep repository development rules out of published plugin artifacts so an installed plugin contains only guidance that
	applies to its end-user environment.

## Guidance

When a file ships in a harness artifact, reference only paths that also ship in that artifact. Do not reference
repository-only `.cat/rules`, source files, tests, or documentation from installed plugin code, skills, hooks, or
launchers.

Treat `.cat/rules/**` as repository development guidance. Distribution assembly must not copy, include, route, or
validate it as a published plugin artifact. Package only the shared plugin-rule sources and the applicable
`client/<harness>/rules/**` sources; a project rule may inform development of those artifacts, but it is not runtime
guidance for an installed plugin.
