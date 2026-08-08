## Strings

## Design Goals

- Handle Java strings and text deterministically, safely, and with readable validation boundaries.

## Guidance

Use a text block, rather than concatenated or escaped strings, for static text whose semantic value spans multiple
physical lines, including JSON and regular expressions. Keep an ordinary string for a single physical line. Use
`String.formatted()` for dynamic values instead of manually escaping and concatenating a JSON string.

For strings from external input (JSON, environment variables, command-line arguments, or hooks), use `isBlank()` when
whitespace-only input is semantically empty. Use `isEmpty()` only when whitespace is meaningful or when checking a value
constructed locally.

Use an empty string rather than `null` when absence and emptiness have the same meaning. Use `null` only when it has a
single distinct, documented meaning; do not return `null` to signal an error.
