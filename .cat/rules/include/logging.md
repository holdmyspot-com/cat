## Logging

## Design Goals

- Make operational logging useful without exposing sensitive information.
- Attribute each log entry to the actual emitting class without retaining unnecessary static logger state.

## Guidance

For each operational log entry, state the event and only the safe context needed to diagnose or correlate it, such as
the operation, stable identifier, outcome, or failure. Do not log credentials, tokens, secrets, private content, or
other sensitive values; use a safe identifier or redacted summary when that context is needed.

Use a non-static logger instance field. A non-final class uses `LoggerFactory.getLogger(getClass())`; a `final` class
uses its class literal. In a static method, create the logger locally when needed. Do not declare static logger fields.
