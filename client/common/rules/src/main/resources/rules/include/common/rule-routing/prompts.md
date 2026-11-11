# Prompt and Rule Rule Routing

## Design Goals

- Route each decision about adding, updating, or enforcing a prompt file or another artifact with declared goals or
	contracts to its focused rule before the reader changes that artifact, so that every prompt-file change is checked
	for consistency between its goals, guidance, triggers, and coverage.

## Guidance

When adding, updating, moving, splitting, merging, renaming, removing, regenerating, reviewing, or auditing a prompt
file, lazy load `../rule-organization.md`. A prompt file is a rule, route index, skill, agent definition, command,
workflow definition, or other prompt text that CAT ships.

When creating, regenerating, materially revising, reviewing, or auditing an artifact with declared goals or contracts
that is not a prompt file, lazy load `../contract-design.md`.

When writing or changing a prompt instruction that requires an outcome or behavior, or removing or narrowing a
mechanism that such an instruction relies on, lazy load `../prompt-prerequisites.md`.

When a project-owned CLI, API, validator, generator, or other callable tool must enforce a workflow requirement, lazy
load `../rules-own-tool-requirements.md`.
