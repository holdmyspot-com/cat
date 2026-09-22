# Skill Interruption Reporting

## Design Goals

<!-- A valid skill instruction can interrupt requested work, but users previously had no direct way to distinguish that
     requirement from an agent's interpretation or locate the operative companion instruction. -->
- Let the user identify and assess the exact skill instruction behind a permission request, pause, unfinished requested
  work, or divergence from the user's intent, including whether the effect is required or inferred.

## Guidance

If a skill causes you to ask for permission or confirmation, pause, leave requested work unfinished, or diverge from the
user's intent, name and link to the exact `SKILL.md` file you read, quote the relevant instruction, and briefly explain
how it applies. Distinguish explicit skill requirements from your interpretation of guidelines.

When the relevant instruction is in a companion file loaded by the skill, also link that exact file and quote the
instruction from there. Retain the `SKILL.md` link to identify the skill's entry point.
