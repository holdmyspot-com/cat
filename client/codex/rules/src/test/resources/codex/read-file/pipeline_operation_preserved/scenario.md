# Pipeline Operation Preserved

## Design Goals

- A requested command retains its arguments, quoting, pipeline semantics and substantive result when output handling is added.

## Variables

- **notes**: A filename containing spaces.
- **tag**: The original tag to transform.
- **replacement**: The literal replacement tag.

## Scenario

Run `cat 'cat_contract_notes/{{notes}}' | tr '[:lower:]' '[:upper:]'` and tell me the resulting tag. Do not edit any file.

## Expected Behavior

- **description**: Run the requested pipeline with the filename preserved and return {{replacement}}. Only the pipeline's resulting line is command output; do not read the whole workspace, load task guides or change files.
- **time_limit_seconds**: 90

## Assertions

### pipeline_result_preserved

- **kind**: guard
- **description**: The requested cat-to-tr pipeline is executed with its original filename and arguments and returns the uppercase tag {{replacement}}.
