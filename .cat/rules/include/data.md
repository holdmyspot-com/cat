## CAT Data Structures

## Design Goals

- Keep CAT data structures explicit, stable, and easy to validate.
- Process independently handleable unbounded input with bounded working memory.

## Guidance

Treat CAT-owned JSON and YAML as versioned data contracts. Parse and validate their shape at the boundary, reject
malformed required fields with a typed error, and model only fields in the current contract. Reject unknown fields
unless the current contract explicitly defines an extension field. Do not expose Jackson nodes beyond the parsing
boundary when a focused domain value can represent the contract.

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
