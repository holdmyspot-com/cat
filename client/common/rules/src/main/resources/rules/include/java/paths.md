# Java Paths

## Design Goals

- Keep a filesystem path typed as `Path` while Java code holds it, so the compiler tells paths apart from other text
  and path operations follow the platform's rules rather than string manipulation.

## Guidance

Declare a field, record component, parameter, return type, local variable or collection element that holds a path on
the local filesystem as `java.nio.file.Path`, not `String`. Convert text to a `Path` where it enters the code, such as a
command-line argument, an environment variable or a value read from a file, and convert a `Path` to text only where
text leaves, such as a message, an argument for another process or a persisted record. Combine and compare paths with
`Path` methods such as `resolve`, `relativize`, `getParent`, `normalize` and `startsWith`, not with string
concatenation, `split` or `String.startsWith`.

Keep a `String` only for a strong reason, and state it beside the declaration when the code does not make it obvious:

- The value names a path in a namespace that the local filesystem does not define, such as a Git path, an archive entry,
  a URL path or a path on another machine. A `Path` would apply the local operating system's separators and naming
  rules to it.
- The declaration is the text form that an external format or API defines, such as a component of a record that maps a
  JSON document, and the code converts it to a `Path` as soon as it reads it.
