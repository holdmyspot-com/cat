## Data Structures

## Design Goals

- Keep this project's data structures explicit, stable, and easy to validate.
- Process independently handleable unbounded input with bounded working memory.

## Guidance

Treat JSON and YAML that this project owns as versioned data contracts. Parse and validate their shape at the boundary,
reject malformed required fields with a typed error, and model only fields in the current contract. Reject unknown
fields unless the current contract explicitly defines an extension field. Do not expose Jackson nodes beyond the parsing
boundary when a focused domain value can represent the contract.

Spell every key and every symbolic value in project-owned JSON and JSONL in snake_case, such as
`{"format_version": 1, "delivery_result": "no_change", "requirement_id": "exact_motto_closing"}`. Symbolic values
include fixed vocabulary, such as types, statuses, and results, and identifiers that CAT, its users, or its models
name, such as requirement, behavior, trial-label, scenario, run, and campaign identifiers. Name a file or directory
that carries such an identifier with the same snake_case spelling. Free text, such as messages, paths, and prompts,
keeps its content. A value in an established external format, such as a timestamp, UUID, hash, or version, keeps that
format; join it to another part of a CAT identifier with an underscore. JSON whose format another tool owns, such as a
harness settings file or protocol message, keeps that owner's spelling.

When input can grow independently of the current operation and its records can be handled independently, keep working
memory bounded: read one record or a bounded chunk, process it, and discard it before reading the next. Use a buffered
reader or streaming parser for JSONL, rollback journals, logs, line-oriented exports, and similarly unbounded record
streams. Do not use `Files.readAllLines()`, `Files.readString()`, `readAllBytes()`, or a stream terminal operation such
as `toList()` merely to process one record at a time.

The same requirement applies beyond text: copy large binary input in bounded buffers and consume producer output while
it is produced rather than accumulating it for a later pass. Keep only aggregates, explicit look-behind, or other state
whose size has a documented bound. If the operation genuinely requires global state, use an external or spillable
algorithm, or enforce and validate a documented input-size limit before loading it all. Whole-document parsing remains
appropriate for small, bounded configuration or an inherently global document contract; state why that bound or global
structure is required at the read boundary.
