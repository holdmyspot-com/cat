# Times Written in the Correct Timezone

## Design Goals

- Prove that an agent presents a time to the user in the session's local timezone, writes a time for a file or tool in
  UTC, and switches either default only for the request in which the user explicitly asks for another timezone.

## Variables

| Name | Description |
|---|---|
| reset_utc | The UTC instant at which the usage limit resets, in ISO 8601 with a `Z` suffix, between 2026-10-05T12:00:00Z and 2026-10-06T12:00:00Z, at a time when the Eastern date differs from the UTC date, such as `2026-10-06T02:30:00Z` |
| reset_local | The same instant in Eastern Daylight Time, written as date, time and `EDT`, such as `2026-10-05 22:30 EDT` |

## Scenario

### Turn 1

The session runs with `TZ=America/New_York`, so `date` prints an Eastern time on 2026-10-05. A command-line tool prints
`usage limit resets at {{reset_utc}}`.

Tell the user when the usage limit resets, and record the reset time in a new file `reset.json` in the current
directory as the JSON object `{"resetsAt": <time>}`. Do not edit any other file.

### Turn 2

Give me that reset time in UTC.

## Assertions

### local_timezone_from_environment

kind: effect

The retained tool events establish that the executor determined the session timezone from the environment, such as by
running `date`, rather than assuming UTC. A final response that happens to state an Eastern time without evidence of
that determination does not pass.

### turn_one_report_uses_local_time_with_zone

kind: effect

The turn 1 response states the reset as {{reset_local}}, or the equivalent ISO 8601 time with the `-04:00` offset. It
shows the converted calendar date together with the time and an offset or abbreviation that identifies the zone. It
does not present the UTC date or time, or `Z`, as the reset time.

### file_value_uses_utc

kind: effect

The `resetsAt` value in `reset.json` is a UTC timestamp with its zone evident, such as `{{reset_utc}}`. It is not the
Eastern time that the user-facing report states.

### turn_two_uses_requested_zone

kind: effect

The turn 2 response states the reset in UTC, as `{{reset_utc}}` or the same date and time followed by `UTC`, because
the user explicitly asked for UTC. It names the zone and does not substitute the local time.
