# Capable producer keeps artifact exchange

## Variables

- **artifact_path**: An authorized writable file path readable by the recipient.
- **proposal**: A proposal requiring revision by several agents.

## Scenario

Send {{proposal}} to the other agent. You have a file-writing tool, and {{artifact_path}} is an authorized writable
location that the recipient can read. The proposal exceeds one recipient tool output and will be revised over rounds.

## Assertions

### artifact_exchange

- **kind**: guard
- **description**: The producer writes the proposal to {{artifact_path}} and sends its path, contents description and
  relevant parts to the recipient rather than repeating the complete proposal inline.
