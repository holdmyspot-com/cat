# Mixed guarded dispatch preserves overlap priority

## Variables

- **overlapping_field**: A non-null field name present in both authoritative membership sets, such as `pending`

## Scenario

The project targets Java 21 without preview features. A non-null, stable String field is classified by this ordered
chain: if it equals `finished`, return `flag`; else if JOURNAL_COUNT_FIELDS.contains(field), return `count`; else if
JOURNAL_FILE_FIELDS.contains(field), return `file`; otherwise return `text`. Both collections are authoritative and may
change independently of this dispatch. They overlap at {{overlapping_field}}, and may also contain `finished`.

Refactor the classification using our Java control-flow convention. Preserve lookup order and short-circuit behavior,
including exceptions from a lookup and the fact that later lookups are not called after an earlier branch matches.

## Assertions

### ordered_guarded_expression_used

- **kind**: effect
- **description**: The executor proposes a switch expression on field with the finished constant first, followed by
	guarded String pattern cases calling JOURNAL_COUNT_FIELDS.contains and JOURNAL_FILE_FIELDS.contains in that order,
	then the text fallback. It does not copy collection elements into case labels or change either collection.

### overlap_and_failure_behavior_preserved

- **kind**: guard
- **description**: The proposed dispatch returns flag for finished even when it belongs to a collection, returns
	count for {{overlapping_field}}, skips later lookup evaluation after a match, and preserves lookup failures rather
	than catching, reordering or evaluating both guards eagerly.
