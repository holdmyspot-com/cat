# Failure Closure

## Design Goals

- Repair every known instance of one evidenced prompt failure class before a rerun can claim that class is fixed.

Classify the failed behavior, its owner, and the invariant the owner must preserve. Enumerate known sibling prompt
files, routes, and validators that can violate the same invariant. If the enumeration is incomplete, report the gap and
stop rather than repairing one visible example. Otherwise repair the compatible owner set, run its deterministic checks,
then rerun the smallest affected behavioral test.
