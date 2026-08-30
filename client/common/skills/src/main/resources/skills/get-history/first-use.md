# Get Session History

## Design Goals

- Retrieve only the explicitly selected conversation transcript and report a deterministic, bounded result.
- Keep transcript lookup read-only and distinguish a missing transcript from a transcript that contains no match.

## Workflow

1. Use the harness-specific `get-history` launcher resolved by the selected harness skill. Supply the requested session
   or thread identifier; do not derive or select a transcript path in the prompt.
2. If that launcher does not exist, use the
   source-built launcher only when the current directory is a CAT source-checkout root, verified by
   `client/pom.xml`. Build it with `client/mvnw -f client/pom.xml -pl distribution -am package`, then run
   `client/common/distribution/target/jlink/<harness>/bin/get-history`, replacing `<harness>` with the selected
   harness.
   Record the launcher path and artifact revision. If neither launcher exists, report the harness-specific location that
   was checked and whether the source-checkout verification failed, then stop.
3. Run one read-only command with the resolved launcher, using the history identifier form documented by the selected
   harness skill. That skill shows the required identifier before the operation and the optional Claude Code agent
   selector. For a progress review, start with `summary`, adding time, event-type, and record-count bounds when the
   question identifies them. It reports only counts, types, timestamps, and whether the limit truncated the result.
   Use an operation that returns original JSONL records only when the question needs the selected records themselves.

4. The command first prints `transcript=<absolute path>`, then its result. Treat an empty operation result as evidence
   that
   the selected transcript has no match. Treat a non-zero exit as a failed retrieval, report its precise diagnostic, and
   do not infer missing conversation content.

## Result

`analyze` reports the number of valid JSONL records and their event types. `summary` reports bounded event metadata
without transcript text. The other operations return the original matching JSONL records in source order; `search` also
returns the requested neighboring records.
