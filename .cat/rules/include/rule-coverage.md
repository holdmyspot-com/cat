# Rule Coverage

## Design Goals

- Identify authoritative rule sources and provide complete, evidenced coverage whenever a rule or systematic requirement
	is applied across existing artifacts.
- Route specialized coverage audits to the focused rule that defines their candidates and evidence.

## Guidance

### Applicable Requirement Set

When asked to apply a rule, convention, policy, migration, or systematic correction across existing artifacts—or when a
user reports a recurring or representative violation of an established requirement—derive a coverage plan before
editing.

When the request applies updated conventions, rules, policies, or standards in the plural, derive the complete
applicable requirement set before defining candidates. Select requirements by artifact type and requested scope,
including relevant always-on and lazily routed rules. Do not treat a reported example or the most recently discussed
requirement as a scope limit unless the user explicitly does so.

Before concluding that a governing rule is absent or creating a new one, inventory every rule source for the current
context: project rules, bundled rules, harness-specific layers, routing indexes, and included files. Follow each routing
reference before deciding its authority. Treat a searched directory as one candidate source, not as the scope boundary;
record any excluded source and its reason. Update an existing authoritative rule rather than duplicating it.

### Candidate Inventory

Treat examples in a requirement as illustrations, not as an exhaustive candidate definition. Derive each candidate
search from the requirement's generalized predicate and artifact scope, then classify every candidate against that
predicate. For example, when applying a static-import convention, scan all qualified constant references in scope and
classify each by the convention's context test; do not search only for constants named in examples.

Load a focused coverage rule only when its observable task condition applies:

- When deciding whether guidance belongs in a bundled plugin rule or this repository's project rules, lazy load
	`${CAT_PROJECT_ROOT}/.cat/rules/common/rule-coverage/plugin-rule-placement.md`.
- When auditing a value or requirement expressed through names, paths, configuration, commands, generated content, or
	another derived representation, lazy load
	`${CAT_PROJECT_ROOT}/.cat/rules/common/rule-coverage/semantic-representations.md`.

For each requirement, define its observable violation, the files and artifact types in scope, an auditable check that
finds every candidate, and any excluded paths with their reasons. Inspect and classify every candidate; do not infer
complete coverage from a reported example, recently edited files, or a representative sample. Do not begin edits until
every in-scope requirement has a check or an explicit blocker.

### Completion Evidence

Fix every in-scope violation or report each blocker explicitly. After editing, rerun every coverage check and the
relevant quality gate. Report the checked scope, exclusions, remaining blockers, and verification results.
