---
depends-on:
  - ./write-clearly.md
---
# Identifier Naming

## Design Goals

- Make every affected type, function, and method name communicate the responsibility or behavior and the target that
  its contract, callers, and implementation establish, and verify that across all comparable identifiers rather than by
  searching for particular name fragments.
- Make every affected variable declared outside a function body, such as a field, constant, or module-level variable,
  name what its value represents and the role the code gives it, so a reader can tell it apart from any other value of
  the same kind without reading the code that uses it.
- Keep a type's name accurate after its responsibility changes, so readers do not infer an earlier or narrower job from
  a name that no longer describes the current one.
- Make the names of related functions and methods use the same words for the behavior they share and matched words for
  the part that differs, so a reader can tell from the names alone how their behavior differs.

## Guidance

When identifier naming is in scope, list every affected type, function, method, and variable declared outside a
function body in the relevant source languages. For a change or its review, an identifier is affected when the change
adds or renames it, changes its responsibility, or changes what a variable holds; do not propose renaming an unchanged
identifier while reviewing an unrelated edit. A requested naming audit affects every identifier in its stated scope.
For each one, use its contract, callers, and implementation to state its responsibility or behavior and its target in
plain language. Then check that its name communicates that same statement under the Clear Writing naming criteria.
Correct each name that fails the check, or report it when renaming is outside the task's scope.

Name a type after its primary, long-lived responsibility: the state it owns, the decision it makes, or the service it
gives its callers. Do not name it after its storage mechanism, file format, container, first caller, current test, or
temporary implementation role unless that detail is the responsibility its callers depend on. For example, a type that
persists credentials, pending sign-in attempts, and cached authorization in a file is an authentication store, not a
session file; a type whose job is to provide a queue backed by a file is correctly named a queue file. Before choosing
the name, identify every intended consumer and check that the name stays accurate if the original caller disappears.

When a change adds to, removes from, or otherwise alters a type's responsibility without renaming it, restate the
current responsibility and compare the existing name with it. A name that described the earlier responsibility but now
hides part of the current one fails the check even when the type's design is sound: correct the name instead of
splitting a coherent type to fit it.

Name a variable declared outside a function body after what its value represents: the record, message, resource, or
domain set its contents come from, and the role the code gives them. Do not name it after only its container or element
kind, such as `FIELDS`, `NAMES`, `KEYS`, or `VALUES`; such a name fits every collection of that kind and leaves the
reader to search its uses to learn which items it holds and why. For example, a set of the hook-input fields that a
handler parses, while it skips every other field, is `PARSED_HOOK_FIELDS`, not `FIELDS`. Omit a word that the
enclosing type's name already supplies, as the Clear Writing qualifier check allows, but keep the word that states the
role.

For a function or method, distinguish one that returns an already-known value from one that searches for a value,
chooses among candidates, converts input, makes a value canonical, checks a condition, creates something, or removes
something. Use equally specific names for other distinct behaviors.

For a function or method, write the plain-language statement before proposing a name, in the terms that its contract
and domain already use: what it accepts, where it looks, how it selects when several candidates qualify, and what it
returns or changes, including its result when nothing qualifies. Derive candidate names from that complete statement
rather than by extending the current name. Revising an existing name one distinction at a time keeps a term that the
contract does not use and leaves each remaining hidden distinction for another revision.

The statement is not a template for the name. Sort the inputs by role: those that locate the data, those that change
which result is selected, and those the callable only checks. Then note what the enclosing type, the parameter types,
and the arguments already tell a caller. Keep a distinction in the name only when a reader of a call site who sees the
arguments would otherwise infer a different scope, selection, or result. An input that only locates data or is only
checked does not need a word in the name, and neither does a failure result that the verb already implies.

Compare a concise candidate with a more explicit one by reading each in an actual call site. Reject a candidate that
suggests a different scope, selection rule, or result, or that drops a distinction the call site needs. Read each
remaining candidate in order: every qualifier must attach to the word it modifies, and the verb's apparent object must
be what the callable returns or changes. Choose among the candidates that remain under the Clear Writing naming
criteria.

Name related functions and methods together. Use the same words in the same order for the behavior they share, and
matched words of one kind for the part that differs, such as `First` and `Last` for two positions in one order rather
than `First` and `Latest`.

For example, two methods of a transcript reader take a transcript and a prompt ID, find the turns recorded for that
prompt, and return the ID of the record that starts the first or the last of those turns, or null when there is none.
A further agent ID argument is only checked against the transcript. The methods are `findFirstTurnStartIdForPrompt` and
`findLastTurnStartIdForPrompt`:

- `findFirstTurnStartId` reads as the transcript's first turn, even beside the prompt ID argument.
- `findFirstPromptTurnStartId` can mean the turn of the transcript's first prompt.
- `findPromptFirstTurnStartId` reads as a search for a prompt.
- `findLatestTurnStartIdForPrompt` suggests the most recent turn by time rather than the last by position.

The agent ID and the null result add no word to either name. A simple accessor needs no added qualifier: an order's
`getId()` returns a known value whose meaning the owning type supplies, so `getOrderId()` only repeats the type.

When a callable's behavior depends on a local representation of an external actor, resource, or boundary, name the
represented semantic target rather than only the local mechanism. In particular, a name for a lifecycle transition must
identify whose state changes when that identity distinguishes the operation; do not reduce a descendant process's
standard-error output to an unspecified output stream. For example, distinguish a parent consuming a forked child's
standard-error output from a method that observes an unrelated output close. A direct operation on a resource with no
distinct represented role does not need an invented actor name.

Use a product or framework name in an identifier only when that product's behavior distinguishes the identifier's
contract. Otherwise name the applicable generic role, such as a parent process or child process.

Do not use a search for particular name fragments as evidence that the audit is complete. An identifier can have an
inaccurate name without containing an expected fragment. Review every in-scope type, function, method, and variable
declared outside a function body that performs the relevant responsibility or holds the relevant value against its
contract and implementation.
