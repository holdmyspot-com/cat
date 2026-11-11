# Get Session History

## Design Goals

- Retrieve only the explicitly selected conversation transcript, through the installed launcher of the selected
	harness or, in a CAT source checkout, the launcher built from that checkout, and report what the launcher returns.
- Keep transcript lookup read-only and distinguish a missing launcher or a failed retrieval from a transcript that
	contains no match.
- Identify a source-built launcher that produced a result by its path and artifact revision.

## Workflow

1. Use the harness-specific `get-history` launcher resolved by the selected harness skill. Supply the requested session
	or thread identifier; do not derive or select a transcript path yourself, in the prompt or in a shell command.
2. If that launcher does not exist, report its exact path, then use the source-built launcher only when the current
	directory is a CAT source-checkout root, verified by `client/pom.xml`. Build it with
	`client/mvnw -q -f client/pom.xml -pl common/distribution -am package`, then run
	`client/common/distribution/target/runtime/<harness>/bin/get-history`, replacing `<harness>` with the
	selected harness. Record the launcher path and artifact revision. If neither launcher exists, report whether the
	source-checkout verification failed, then stop.
3. Run one read-only command with the resolved launcher, using the history identifier form documented by the selected
	harness skill. That skill shows the required identifier before the operation and the optional Claude Code agent
	selector.
4. The command first prints `transcript=<absolute path>`, then its result. Treat an empty operation result as evidence
	that the selected transcript has no match. Treat a non-zero exit as a failed retrieval, report its precise
	diagnostic, and do not infer missing conversation content.

## Result

`analyze` reports the number of valid JSONL records and their event types. The other operations return the original
matching JSONL records in source order, unless the selected harness skill documents another result form for an
operation; `search` also returns the requested neighboring records.
