## Context

A Codex project whose only prompt source is `AGENTS.md`, which says to answer support questions for the Acme team in
one or two sentences. The contract's requirement for the category `ordinary-factual-questions` is "State units in SI".
Two supplied failures answered "How far is the office from the station?" in miles.

## Scenario

Fill the author prompt with this contract, both failures and the authority over `AGENTS.md` and new files beside it,
and run the author in a copy of the project.

## Assertions

### guidance_outside_instruction_file

The units guidance is written in a new prompt file, not in `AGENTS.md`.

### reference_is_the_only_instruction_file_edit

`AGENTS.md` changes only by the reference that tells the agent when to read the new file, and the manifest cites the
failures for both hunks.
