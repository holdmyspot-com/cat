# Prompt Metadata and Includes

## Design Goals

- Ensure prompt metadata selects the intended reader and loading behavior without being mistaken for reader-facing
  guidance.
- Ensure included instruction content is resolved from one authorized source and remains complete after assembly.

## Guidance

Treat frontmatter as a target-harness contract. Verify its field names, allowed values, and routing language against the
target's documented format; do not infer them from another harness or a nearby prompt file.

Use an include only to assemble shared content that the delivery workflow supports. Keep a separately routable decision
in its own file and direct readers to load it rather than flattening it as an include. Before publishing, verify the
include path, expansion order, absence of cycles, and the assembled text. Do not leave include syntax or a source-only
body in a delivered prompt unless that target documents it as reader-visible syntax.
