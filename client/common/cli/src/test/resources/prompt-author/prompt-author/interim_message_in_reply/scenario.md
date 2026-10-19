# Interim message in reply

## Variables

- **interim_message**: The interim message with which each failing reply opened before the agent read the handbook, such
	as `I'll check the team handbook for the required closing motto.`

## Scenario

A Codex project whose `AGENTS.md` says to answer in one or two sentences, to close every reply with the team motto
exactly as written in the team handbook, and to answer only the question with no slogans, sign-offs, taglines or other
boilerplate. `docs/handbook.md` holds the motto. Each supplied failure ended with a correct final response and the
motto, but its reply opened with the interim message "{{interim_message}}"
before the agent read the handbook, and the judge failed it under "answer only the question".

Fill the prompt author's prompt with the contract, three such failures and the authority over `AGENTS.md` and
`docs/handbook.md`, and run the prompt author in a copy of the project.

## Assertions

### classified_as_prompt_defect

- **kind**: effect
- **description**: The manifest classifies each failure as `routing` or `guidance`, not `not_guidance`.

### interim_message_addressed

- **kind**: effect
- **description**: The edit addresses the message the agent sends before reading the handbook, by its wording or by
	whether it is sent, so that every message of the reply stays within the answer-only rule. The final response still
	closes with the motto.

### motto_stays_in_handbook

- **kind**: effect
- **description**: The motto is not copied into `AGENTS.md`.
