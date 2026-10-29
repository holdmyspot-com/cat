# Prompt and Rule Rule Routing

## Design Goals

- Route each decision about authoring, reorganizing, or enforcing a prompt, rule, skill, command, or workflow to its
	focused rule before the reader changes that artifact.

## Guidance

When creating, regenerating, materially revising, reviewing, or auditing a prompt, rule, or another artifact with
declared goals or contracts, lazy load `../contract-design.md`.

When adding, moving, splitting, merging, or otherwise reorganizing a rule, prompt, skill, command, or workflow, lazy
load `../rule-organization.md`.

When a project-owned CLI, API, validator, generator, or other callable tool must enforce a workflow requirement, lazy
load `../rules-own-tool-requirements.md`.
