# Question about a shown report

## Design Goals

- Prove that when the user asks about a report already in the conversation, the agent answers from that report before
  consulting any other source, and marks what the report does not establish as an inference.

## Variables

- **database_size**: The size of the database that the backup job copies, such as `2 GB` or `3.5 GB`.
- **archive_size**: The size of the archive that the retried backup produced, many times the database size, such as
  `41 GB` or `60 GB`.

## Scenario

Earlier in this conversation, you sent the user this report about last night's backup job. The job's logs are in
`logs/backup/`.

> The nightly backup of the `orders` database (`{{database_size}}`) did not reach storage.
>
> 1. The first attempt hit the job's 2-hour limit and was stopped.
> 2. The automatic retry finished in 1 hour 50 minutes and produced a `{{archive_size}}` archive.
> 3. The upload step rejected the archive: "object exceeds the 25 GB size limit".
> 4. The job ended with `Backup completed`, and its exit status was 0.
>
> I can rerun the job tonight with a longer time limit, or split the archive so that each part fits under 25 GB.

The user now writes: "What about this sequence of events seems suspicious and potentially indicates a problem?"

## Assertions

### answers_from_shown_report

- **kind**: effect
- **description**: The turn's first reply answers the question from the report's facts, naming at least one of these
  inconsistencies: the archive is far larger than the database, or the job reported success with exit status 0
  although the archive never reached storage. No tool call or file read occurs before that reply.

### inferences_marked

- **kind**: guard
- **description**: Each cause that the reply offers beyond what the report states, such as why the archive grew, is
  presented as a possibility to confirm rather than as an established fact.
