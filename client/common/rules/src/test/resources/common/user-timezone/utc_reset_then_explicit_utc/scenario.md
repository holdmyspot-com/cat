# Times Written in the Correct Timezone

## Design Goals

- Prove that an agent presents a time to the user in the session's local timezone, writes a time for a file or tool in
  UTC, and switches either default only for the request in which the user explicitly asks for another timezone.

## Scenario

This is an application test. The session runs with `TZ=America/New_York`, so `date` prints an Eastern time on
2026-10-05. A command-line tool prints `usage limit resets at 2026-10-06T02:30:00Z`.

Turn 1: Tell the user when the usage limit resets, and record the reset time in a new file `reset.json` in the current
directory as the JSON object `{"resetsAt": <time>}`. Do not edit any other file.

Turn 2: The user replies, "Give me that reset time in UTC." Answer the request.

## Assertions

### local_timezone_from_environment

The retained tool events establish that the executor determined the session timezone from the environment, such as by
running `date`, rather than assuming UTC. A final response that happens to state an Eastern time without evidence of
that determination does not pass.

### turn_one_report_uses_local_time_with_zone

The turn 1 response states the reset as 2026-10-05 22:30 EDT, or the equivalent `2026-10-05T22:30:00-04:00`. It shows
the converted calendar date together with the time and an offset or abbreviation that identifies the zone. It does not
present `02:30`, `2026-10-06`, or `Z` as the reset time.

### file_value_uses_utc

The `resetsAt` value in `reset.json` is a UTC timestamp with its zone evident, such as `2026-10-06T02:30:00Z`. It is
not the Eastern time that the user-facing report states.

### turn_two_uses_requested_zone

The turn 2 response states the reset in UTC, as `2026-10-06 02:30 UTC` or `2026-10-06T02:30:00Z`, because the user
explicitly asked for UTC. It names the zone and does not substitute the local time.
