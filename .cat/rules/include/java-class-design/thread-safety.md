# Java Thread Safety

## Design Goals

- Give each Java type a thread-safety contract that matches its intended concurrent use, with state types that keep
	that contract and no thread-safe types added only as speculative protection.
- Let a caller learn from a type's documentation whether it may use the type concurrently and which state the type
	protects, including how long the type keeps any mutable argument that the caller must then not change.
- Let a caller that holds an interface or abstract class rely on its documented thread safety of implementations: every
	implementation meets it, and it claims only what the type can guarantee.
- Let a caller learn, apart from thread safety, what a type guarantees when other processes use the same files,
	directories, or other outside resources, and which restriction the caller must honor otherwise.
- Let every caller act on each note: the same contract always reads the same, and each condition that a note puts on
	callers names the mechanism that lets them meet it.

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

### Thread Safety Notes

Document thread safety only for types intended to provide that contract, in a `<b>Thread Safety:</b>` note that opens
with exactly one of these statements:

- `This class is thread-safe.` when the type's own code provides the guarantee, including records and enums.
- `Implementations are thread-safe.` when a sealed interface or abstract class leaves the guarantee to its
	implementations, which are all known.
- `Implementations must be thread-safe.` when other code can implement or extend the type, because it cannot vouch for
	implementations that it does not know.

Use no other wording, such as "may be shared across threads", "is immutable", "is stateless", or "can be used from any
thread". After the statement, add only guarantees that callers can observe, such as which calls never overlap or what
a concurrent call sees. An undocumented type is assumed not thread-safe, so a type that is not thread-safe gets no note.
When only some of a type's methods may run concurrently, give the type no note and state on each such method which
calls it may overlap. Every implementation of a type that states either `Implementations` statement must be thread-safe;
the thread-safe types and synchronization it needs for that are required, not speculative.

### Process Safety Notes

Document a type that reads or writes files, directories, or other outside resources that another process may use at
the same time in a `<b>Process Safety:</b>` note, placed after its Thread Safety note if it has one. Omit the note for a
type that uses no such resource; a Thread Safety note never covers outside resources. Open the note with exactly one of
these statements:

- `This class is process-safe.` when other threads and processes may use the same resources at any time. Then state
	what they observe, such as that a reader sees a replaced file's previous or new content, never part of it, or which
	result remains when two writers race.
- `This class is not process-safe.` otherwise. Then state any restriction that callers must honor, and the guarantees
	that hold, including those that hold only while callers honor the restriction.
- `Implementations are process-safe.` or `Implementations must be process-safe.`, chosen as for thread safety.

### Conditions and Implementation Details

In either note, state a condition on callers only when the code provides the mechanism that lets them meet it, such as
a lock that callers take, and name that mechanism with `{@link}`. A note that needs a condition without such a
mechanism is wrong: change the type so that it needs no condition or provides the mechanism, or change the note so that
it claims only what the code guarantees, down to a bare `This class is not process-safe.` or no Thread Safety note.
Neither note says how the type keeps its guarantees, such as whether it is immutable, which internal locks or
synchronization it uses, what it caches, or which temporary files it writes: those are implementation choices.

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

/**
 * Stores the application's settings in one file.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 * <p>
 * <b>Process Safety:</b> This class is not process-safe. Only the process that holds the {@link SettingsLock} may
 * write the settings. A reader in any process sees the file's previous or new content, never part of it.
 */
public final class SettingsFile
{
}
```
