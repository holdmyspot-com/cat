# Prompt and Rule Rule Routing

## Design Goals

- Route each decision about changing or enforcing a prompt file or another artifact with declared goals or contracts to
	its focused rule before the reader changes that artifact, so that every prompt-file change that alters the text an
	agent receives or when it receives it is checked for consistency between its goals, guidance, triggers, and
	coverage, and a change that alters neither is not.

## Guidance

When adding, updating, moving, splitting, merging, renaming, removing, regenerating, reviewing, or auditing a prompt
file in a way that changes the text any agent receives or when it receives it, lazy load `../rule-organization.md`.
A prompt file is a rule, route index, skill, agent definition, command, workflow definition, or other prompt text that
CAT ships. The change qualifies when it alters a file's content beyond updating references to a new path, alters which
files load, or alters the condition under which one loads, for example a rename or move that resolves or breaks a
reference, or places a file in or out of an always-loaded or path-scoped location. A rename or move whose only effect
is the new path, with every reference updated so that the same text loads under the same conditions, does not qualify;
verify only that no reference names the old path.

When creating, regenerating, materially revising, reviewing, or auditing an artifact with declared goals or contracts
that is not a prompt file, lazy load `../contract-design.md`.

When writing or changing a prompt instruction that requires an outcome or behavior, or removing or narrowing a
mechanism that such an instruction relies on, lazy load `../prompt-prerequisites.md`.

When a project-owned CLI, API, validator, generator, or other callable tool must enforce a workflow requirement, lazy
load `../rules-own-tool-requirements.md`.
