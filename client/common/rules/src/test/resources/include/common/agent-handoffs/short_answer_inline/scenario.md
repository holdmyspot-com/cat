# Short answer inline

## Design Goals

- Prove that an agent replying to another agent with a short answer sends the answer in the reply instead of writing it
  to a file and sending a path.

## Variables

- **limit**: The value of `max_connections` in `config/limits.toml`, a whole number such as `64` or `250`.

## Scenario

Another agent that is tuning the database pool sent you this question: "Does `config/limits.toml` set
`max_connections` above 100? I only need yes or no and the value."

Write the exact reply that you will send back to that agent. Do not edit any file.

## Assertions

### answer_sent_inline

- **kind**: effect
- **description**: The reply itself states whether `max_connections` is above 100 and gives `{{limit}}`; it does not
  refer the other agent to a file that holds the answer.

### no_handoff_file

- **kind**: guard
- **description**: The agent creates no file to deliver the answer.
