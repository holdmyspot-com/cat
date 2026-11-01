# Prompt Prerequisites

## Design Goals

- A prompt's required outcomes and behaviors are executable in every delivery context, with prerequisite evidence or
	an explicit condition and reader action for absence as defined below.
- Removing or narrowing a providing mechanism preserves executable requirements in dependent prompts, verified again
	for each affected context.

## Guidance

Apply this check while writing or changing prompt instructions that require a reader to produce an outcome or behave
a certain way. This includes rules, skills, agent definitions, command prompts, hook output, and prompt text generated
by code. Also apply it before removing or narrowing a mechanism on which such an instruction relies.

Identify every context where the instructions are delivered. Inspect their delivery and setup contracts for supported
harnesses, platforms, and session kinds, including interactive, nested, automated, sandboxed, and tool-restricted
subagent sessions where applicable.

For each required outcome or behavior, list its prerequisites: state, files, permissions, tools, credentials,
configuration, environment values, and prior steps. In each context, name the mechanism that establishes each
prerequisite before the instruction needs it. Inspect the provider's actual contract or behavior, whether it is another
prompt instruction, a hook, launcher or runner setup, a tool contract, or a documented harness default. The instruction
itself may establish a prerequisite. Do not infer provision from a mechanism's name or the author's own environment.

When a prerequisite has no verified provider in a context, resolve it before accepting the instruction:

1. Add or extend a mechanism that establishes it before use.
2. Make the instruction establish it first.
3. Condition the instruction on it and state how the reader checks it and what to do when it is absent.
4. Remove or narrow the requirement.

Recheck the chosen resolution in each affected context. Do not leave the reader to discover or improvise a missing
prerequisite. A user-supplied request input may remain an explicit input; it does not need a setup mechanism. An
instruction that already establishes its prerequisites or explicitly handles their absence needs no additional remedy.

Before removing or narrowing a providing mechanism, find the prompts whose requirements depend on it and repeat this
check for every affected requirement and delivery context. Resolve missing providers before changing the mechanism.

For each required outcome or behavior, retain either a record of its prerequisites, delivery contexts, and verified
providers, or an observed run that exercises it in each context. For a conditional instruction, include its absence
check and prescribed reader action. A run in a different context does not prove provision in the required one.
