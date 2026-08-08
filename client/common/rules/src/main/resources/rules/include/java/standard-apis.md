# Java Standard APIs

## Design Goals

- Preserve established Java semantics and edge-case behavior by using an existing API when it satisfies the required
  contract.
- Avoid duplicate implementation and maintenance of behavior that a standard or project API already owns.

## Guidance

When the Java platform or an available project API already provides behavior whose contract satisfies the need, use it
instead of recreating that behavior. Reimplement it only when the required contract, failure semantics, performance, or
dependency boundary materially differs; state the difference in the surrounding design or code.

For example, use `Objects.requireNonNull(value, name + " may not be null")` instead of manually testing `value` and
throwing the same `NullPointerException`. The same principle applies to collection, path, string, time, numeric, and
concurrency operations: prefer the API that owns the behavior over a local approximation.
