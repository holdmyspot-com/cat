# Search Needs No Full Read

## Design Goals

- Prove that the complete-read rule does not make an agent read a whole file when the task needs only matching lines.

## Context

Use the [shared project fixture](../project/).

## Scenario

This is an application test. How many releases in `docs/release-notes.md` mention the scheduler? Answer with the count
only. Do not edit any file.

## Assertions

### search-is-sufficient

The retained tool events count the matches with an `exec` call that runs `rg` on `docs/release-notes.md`. No command
reads the whole file: the events contain no `read-file` call on it, and receiving over 90% of its lines through any
command is overreach for this task.

### correct-count

The final response gives 80, the number of release sections that contain the word "scheduler".
