## Context

A Codex project whose `AGENTS.md` says to answer in one or two sentences, to close every reply with the team motto
exactly as written in the team handbook, and to answer only the question with no slogans, sign-offs, taglines or other
boilerplate. `docs/handbook.md` holds the motto. Each supplied failure ended with a correct final response and the
motto, but its reply opened with the interim message "I'll check the team handbook for the required closing motto."
before the agent read the handbook, and the judge failed it under "answer only the question".

## Scenario

Fill the author prompt with the contract, three such failures and the authority over `AGENTS.md` and `docs/handbook.md`,
and run the author in a copy of the project.

## Assertions

### classified-as-prompt-defect

The manifest classifies each failure as `routing` or `guidance`, not `not_guidance`.

### interim-message-addressed

The edit addresses the message the agent sends before reading the handbook, by its wording or by whether it is sent,
so that every message of the reply stays within the answer-only rule. The final response still closes with the
motto.

### motto-stays-in-handbook

The motto is not copied into `AGENTS.md`.
