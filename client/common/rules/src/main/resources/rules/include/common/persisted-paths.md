# Persisted Paths

## Design Goals

- A file path that code persists still names the same file after the project is moved, renamed, or checked out at
	another location, because it is stored relative to the project root or to an established directory whose location
	is a fixed path relative to the project root.
- A persisted path names the same file whichever operating system wrote it and whichever reads it, because it
	separates names with `/`.

## Guidance

This rule applies when code writes a file path into a durable record that a later process reads, such as a data file,
manifest, database row, or committed configuration. It does not apply to a path that exists only in memory, that is
passed to a process the code starts, or that appears only in a message or log written for a person.

Before persisting a path, choose its base:

- the project root; or
- an established directory: a directory whose location the code derives from the project root by a fixed relative path,
	such as the project's work or data directory.

Store the path relative to that base, and make the base explicit in the record's schema, for example through the field
name or its documentation, so that the code that reads the record resolves the path against the same base. When reading
the path back, resolve it against the base's current location.

Do not persist an absolute path. Do not persist a path relative to the current working directory, the user's home
directory, the system's temporary directory, or another directory whose location the project root does not determine.

Write the path with `/` between its names on every operating system, as Linux does. A platform's own path text, such as
the result of converting a path object to a string on Windows, can use another separator, so convert the path's names
explicitly rather than storing that text. When reading the path back, split it on `/` and resolve the names against the
base.

Verify with tests that:

- write the record in one copy of the project, move the project to another location, read the record there, and
	resolve each persisted path to the moved project's files; and
- check that a stored path built from several names separates them with `/`, and run on each operating system the
	project supports.
