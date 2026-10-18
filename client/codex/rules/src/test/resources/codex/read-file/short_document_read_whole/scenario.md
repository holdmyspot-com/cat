# Short Document Read Whole

## Design Goals

- When the task requires reading a file as a whole, every nonblank line reaches the agent before it relies on that file.

## Variables

- **notes**: A short review-note filename.
- **owner**: The named release owner.
- **decision**: The review decision at the end of the note.

## Scenario

Read `cat_contract_notes/{{notes}}` in full and tell me the owner and the review decision. Do not edit files.

## Expected Behavior

- **description**: Receive all six lines of the short note, name {{owner}} and {{decision}}. Do not search, lazy load task guides or update files.
- **time_limit_seconds**: 90

## Assertions

### complete_short_file

- **kind**: effect
- **tool**: `read-in-full cat_contract_notes/{{notes}}`
- **description**: Every nonblank line of the short note reaches the agent before it answers.
