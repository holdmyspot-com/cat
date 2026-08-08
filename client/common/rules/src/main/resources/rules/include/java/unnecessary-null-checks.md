# Unnecessary Java Null Checks

## Design Goals

- Verify explicit Java null checks against the reachable failure contract rather than one local statement.

## Guidance

When auditing explicit Java null checks, first lazy load `../common/parameter-validation.md`, then inventory every
explicit null validator. Trace each parameter's reachable paths through direct dereferences and unconditionally invoked
callees until its first null failure or caller-visible side effect. Classify the validator as necessary, redundant
because of a direct dereference, or redundant because of a transitive guaranteed dereference. Do not treat an
immediate-method scan as complete when a callee establishes the same failure contract; recheck analogous caller/callee
pairs.
