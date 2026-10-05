# Timezones for Times

## Design Goals

- Let the user read every time and date that an agent presents to them without converting it from UTC or another zone.
- Keep every time that an agent writes for a file, tool, or shared record in one unambiguous zone, so a later reader or
  program does not depend on the session's local zone.

## Guidance

When a response, report, summary, explanation, or other text presented directly to the user states a time or date,
express it in the user's timezone and make the zone evident with an offset or abbreviation, such as `07:30 EDT` or
`2026-10-05T07:30:00-04:00`. Convert the calendar date together with the time, because the date can differ between
zones. Convert every UTC or other-zone timestamp that a tool, log, API, or command returns before presenting it.

The user's timezone is the local system timezone of the session. Determine it from the environment, for example from
the output of `date +"%Z %z"`, which follows `TZ` and `/etc/localtime`; do not assume UTC.

Every other time that you write uses UTC by default and makes the zone evident, such as `2026-10-05T11:30:00Z`. This
covers values in files, commands, data formats, logs, identifiers, commit messages, and other machine-readable output.
When the format that consumes a value requires a different representation, use the required one.

Change either default only when the user explicitly asks for a different timezone, and only for that request. Return to
the defaults for later text unless the user states otherwise.
