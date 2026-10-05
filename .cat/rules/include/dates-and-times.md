## Dates and Times

## Design Goals

- Keep every date and time that this project writes unambiguous, sortable, and readable by standard parsers.

## Guidance

Represent every date and time that this project writes or parses in ISO 8601: in project-owned data, file, directory,
and Git ref names, identifiers, command output, logs, and messages. Write a calendar date as `2026-10-05` and a
date-time as `2026-10-05T15:58:05Z`, with seconds and an explicit `Z` or numeric offset, such as `-04:00`. Record and
exchange instants in UTC. Output written for a person may show that person's time zone if it keeps the offset.

Where a name or identifier cannot hold `:`, such as a file name, Git ref, or campaign identifier, use the ISO 8601 basic
format, such as `20261005T155805Z`, which keeps the `T` and the `Z`. A value whose format another tool owns, such as a
harness's epoch-second field, keeps that format at that tool's boundary; convert it to ISO 8601 before storing or
showing it.

In Java, hold dates and times as `java.time` values rather than numbers or strings, and write them with ISO
formatters, such as `Instant.toString()` or `DateTimeFormatter.ISO_OFFSET_DATE_TIME`. Use
`DateTimeFormatter.ofPattern()` only for the basic format.
