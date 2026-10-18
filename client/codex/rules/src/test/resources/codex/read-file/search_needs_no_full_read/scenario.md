# Search Needs No Full Read

## Design Goals

- A request needing only matching records is answered correctly without reading substantially all of an unrelated long file.

## Variables

- **notes**: The release-notes filename under docs/.
- **release**: The release being searched for, between 4.1 and 4.320.
- **component**: The component improved by this release.
- **change**: The migration sentence for release 4.296.
- **before**: Generated service documentation around the requested release entry.

## Scenario

Look up which component release {{release}} improved in `docs/{{notes}}`. Answer with the component only. Do not edit any file.

## Expected Behavior

- **description**: Use a selective lookup and return {{component}} only, without receiving more than 90% of docs/{{notes}}. Do not perform a full task file read, lazy load unrelated task guides or change files.
- **time_limit_seconds**: 90

## Assertions

### search_is_sufficient

- **kind**: guard
- **tool**: `read-at-most docs/{{notes}} 90`
- **description**: No command reads the whole of `docs/{{notes}}`: receiving over 90% of its lines through any command
  is overreach for this task.

### component_named

- **kind**: guard
- **tool**: `pattern "(?i)\\b{{component}}\\b"`
- **on_tool_failure**: judge
- **description**: The reply names the {{component}} as the component that release {{release}} improved.
