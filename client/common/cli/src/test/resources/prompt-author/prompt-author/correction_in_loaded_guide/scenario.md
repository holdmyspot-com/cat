# Correction in loaded guide

## Variables

| Name | Description |
|---|---|
| question | A support question whose answer is a quantity that the supplied failures stated in non-SI units, such as `How far is the office from the station?` |

## Scenario

A Codex project whose prompt sources are `AGENTS.md` and `docs/style.md`. `AGENTS.md` holds two lines: answer support
questions for the Acme team, and before answering, read `docs/style.md`. `docs/style.md` says to answer in one or two
sentences. The contract's requirement for ordinary factual questions is "State units in SI". Two
supplied failures answered "{{question}}" in non-SI units, after reading `docs/style.md`.

Fill the prompt author's prompt with this contract, both failures and the authority over `AGENTS.md` and
`docs/style.md`, and run the prompt author in a copy of the project.

## Assertions

### guide_file_corrected

kind: effect

The units guidance is added to `docs/style.md`, which the failing sessions already read.

### instruction_file_unchanged

kind: effect

`AGENTS.md` is unchanged.
