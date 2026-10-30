# Code Writing Rule Routing

## Design Goals

- Route each decision made while writing or changing source code or configuration, such as an expression, a name, a
	layout, a declared type, a resource, an environment access, or a grant, to its focused rule before the reader makes
	it.

## Guidance

When writing or changing a boolean expression that contains a negation, or that compares a boolean test with a boolean
literal such as `true` or `false`, lazy load `../boolean-negation.md`.

When writing or changing an expression that combines two different binary or ternary operators without parentheses
between them, lazy load `../operator-precedence.md`.

When writing or changing a condition that combines two or more tests, or that tests a value whose meaning the expression
does not state, such as a date offset, a threshold, or a business rule, or writing or changing a log, exception, or
user-facing error message that a branch emits about its condition, lazy load `../named-conditions.md`.

When defining, changing, reviewing, or auditing type, function, or method names, or the names of variables declared
outside a function body, or changing a type's responsibility without renaming it, lazy load `../identifier-naming.md`.

When writing or editing a method chain or its layout, lazy load `../method-chain-layout.md`.

When writing or editing the declaration of a local variable in Java, C#, C++ or another language where the author writes
either its type or an inference keyword such as `var` or `auto`, lazy load `../local-variable-types.md`.

When writing or changing code that passes a resource it created to a constructor, or a constructor that receives or
creates a resource that must be released, lazy load `../construction-failure-cleanup.md`.

When creating, moving, or renaming a top-level implementation or test type, lazy load `../type-organization.md`.

When accessing environment-dependent state, or writing an absolute path inside a user's home directory into code,
configuration, or tests, lazy load `../environment.md`.

When adding or widening a command-line option, launch or build setting, manifest entry, or module declaration that
grants code a permission or access, or that adds modules to a runtime or image, lazy load `../minimal-grants.md`.

When editing source code or configuration, lazy load `../semantic-blank-lines.md`.
