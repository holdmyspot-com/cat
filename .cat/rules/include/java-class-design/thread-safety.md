# Java Thread Safety

## Design Goals

- Give each Java type a thread-safety contract that matches its intended concurrent use, with state types that keep
	that contract and no thread-safe types added only as speculative protection.
- Let a caller learn from a type's documentation whether it may use the type concurrently and which state the type
	protects, including how long the type keeps any mutable argument that the caller must then not change.
- Let a caller that holds an interface or abstract class rely on its documented thread safety of implementations: every
	implementation meets it, and it claims only what the type can guarantee.

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

Document thread safety only for types intended to provide that contract. State that the type is thread-safe and any
other guarantee that callers can observe, such as what consistency or visibility they can rely on. Do not mention
whether the type is immutable or how it achieves thread safety, such as through synchronization, caching, or race
handling: those are implementation choices. An undocumented type is assumed not thread-safe.

An interface or abstract class may document that its implementations are thread-safe. A sealed type, whose permitted
implementations are all known, may state it as a fact: “Implementations are thread-safe.” A type that other code can
implement or extend may only state it as a requirement, “Implementations must be thread-safe.”, because it cannot vouch
for implementations it does not know. Every implementation of a type that documents either statement must be
thread-safe; the thread-safe types and synchronization it needs for that are required, not speculative.

```java
/**
 * Holds the application's configuration.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Config
{
}

/**
 * Handles one kind of request.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
public interface RequestHandler
{
}
```
