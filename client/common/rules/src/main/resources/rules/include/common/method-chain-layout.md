# Method-Chain Layout

## Design Goals

- Keep each line of a method chain visually continuous without forbidding a wrapped layout that the language permits.

## Guidance

When writing or editing a method chain, do not put spaces or tabs between a completed expression and the
member-access operator, such as `.`, that selects the next method on the same line. A line break at that operator is
wrapping, not inline whitespace: wrap a long chain wherever the language permits a line break there.
