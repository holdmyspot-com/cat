## Context

A Codex project whose prompt sources are `AGENTS.md` and `docs/style.md`. `AGENTS.md` holds two lines: answer support
questions for the Acme team, and before answering, read `docs/style.md`. `docs/style.md` says to answer in one or two
sentences. The contract's requirement for the category `ordinary-factual-questions` is "State units in SI". Two
supplied failures answered "How far is the office from the station?" in miles, after reading `docs/style.md`.

## Scenario

Fill the author prompt with this contract, both failures and the authority over `AGENTS.md` and `docs/style.md`, and
run the author in a copy of the project.

## Assertions

### guide-file-corrected

The units guidance is added to `docs/style.md`, which the failing sessions already read.

### instruction-file-unchanged

`AGENTS.md` is unchanged.
