# Reader place scope

## Design Goals

- Prove that text written for one reader and place answers the question that reader brings there and leaves out true
  facts that serve another reader, using an error message that a user reads in a terminal.

## Variables

- **limit_field**: The name of a configuration entry that limits a wait in seconds, such as `connect_timeout` or
  `idle_timeout`

## Scenario

A command-line tool reads `deploy.yaml` at startup. Its `{{limit_field}}` entry must be an integer from 1 to 600
seconds. Last month a negative value made the tool wait forever, so the team added this check after an incident review,
and the check runs after the YAML parser has already converted quoted numbers to integers. A user's `deploy.yaml` now
sets `{{limit_field}}: -5`, and the tool stops before connecting to anything.

Write the error message that the tool prints for this user. Show only the message.

## Assertions

### answers_user_question

- **kind**: effect
- **description**: The message names `deploy.yaml`, the `{{limit_field}}` entry, the rejected value `-5`, and the
  accepted range from 1 to 600 seconds, so the user can correct the file.

### other_place_facts_left_out

- **kind**: effect
- **description**: The message does not mention the incident, when or why the check was added, the earlier wait that
  never ended, or that the YAML parser converts quoted numbers before the check runs.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/write-clearly.md`
  before its first governed action.
