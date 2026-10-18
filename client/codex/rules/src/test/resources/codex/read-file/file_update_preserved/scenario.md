# File Update Preserved

## Design Goals

- A requested local file update produces the requested contents without unrelated file edits or read-only-command substitution.

## Variables

- **notes**: A note filename.
- **entry**: The entry to append.
- **file_text**: The exact three-line note after the requested append.

## Scenario

Append the line `{{entry}}` to `cat_contract_notes/{{notes}}`, keeping the existing two lines. Report the appended line.

## Expected Behavior

- **description**: The note contains its original two lines followed by {{entry}} and the reply identifies that appended line. Do not perform unrelated full reads, searches, lazy loading or file changes.
- **time_limit_seconds**: 90

## Assertions

### updated_file_contents

- **kind**: guard
- **tool**: `file-content "cat_contract_notes/{{notes}}" "{{file_text}}"`
- **description**: The note retains Release ledger and Entries: on its first two lines and appends {{entry}} as its third line.

### no_unrelated_edits

- **kind**: guard
- **description**: No project file other than cat_contract_notes/{{notes}} is edited as part of the user's task; automatic harness work and wrapper scratch output are not user file edits.
