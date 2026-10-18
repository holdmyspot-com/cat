# Java Thread Safety

## Design Goals

- Give each Java class a thread-safety contract that matches its intended concurrent use, with state types that keep
  that contract and no thread-safe types added only as speculative protection.
- Let a caller learn from a class's documentation whether it may use the class concurrently and which state the class
  protects, including how long the class keeps any mutable argument that the caller must then not change.

## Guidance

Choose types that support the class's intended thread-safety contract. A thread-safe class must use thread-safe types
for shared mutable state; its synchronization, visibility, and collection choices must preserve its documented
observable guarantees. A class not intended to be thread-safe should use ordinary non-thread-safe types when a
reasonable alternative exists. Do not add concurrent collections, synchronization primitives, atomics, or other
thread-safe types merely as speculative protection.

A thread-safety guarantee covers only the state that the type owns: its fields and the objects that only it can reach,
such as those it creates or copies. It does not extend to a mutable argument that the caller can still reach; the caller
remains responsible for not changing that argument from another thread while the type uses it. A thread-safe class
therefore needs no synchronization or copy merely to protect an argument that it uses only during a call. When it keeps
a mutable argument after the call without copying it, document that it keeps the argument, so callers know how long
they must not change it.

Document thread safety only for classes intended to provide that contract. State the observable guarantee, such as
whether callers may invoke public operations concurrently and what consistency or visibility they can rely on—not
synchronization, cache, or race-handling mechanisms. An undocumented class is assumed not thread-safe.

```java
/**
 * Immutable configuration holder.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Config
{
}
```
