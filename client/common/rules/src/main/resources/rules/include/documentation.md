---
depends-on:
  - ./common/write-clearly.md
---

# Documentation Contracts

## Design Goals

- Explain each maintained product, legal, workflow, configuration, or callable interface's observable behavior,
  constraints, rights, obligations, and consequences clearly enough for its intended reader to act correctly.
- Keep replaceable implementation choices outside the documented interface unless they change a decision the intended
  reader must make, so an internal choice does not become a contract that later implementations must preserve.
- Let the reader of each kind of documentation site, such as a type, callable, parameter, value, comment, or document
  section, find there the content listed for that kind of site, and find each other fact at the site whose reader uses
  it.

## Contract Boundary

Before drafting, identify the explicit product, legal, API, or workflow contract. When a supplied fact describes how
the current implementation works, determine whether the contract makes that fact part of the reader-visible interface.
A task mentioning an implementation detail does not make that detail part of the interface.

Omit an implementation detail when readers do not need it to understand or use the explicit contract. Do not introduce
that detail merely to state that it is not guaranteed, is not promised, or may change. Silence leaves the implementation
choice unspecified without making it part of the interface. State a negative boundary when it defines an observable
rejection or failure, a reader's right or obligation, a compatibility boundary, or a security or lifecycle guarantee
needed to use the interface correctly.

This boundary does not exclude purpose, context, definitions, rationale, or examples that help the intended reader
understand the documented contract without exposing replaceable implementation choices.

When a comment or documentation explains a local representation of an external actor, resource, or boundary, name both
the represented role and the concrete value or scope that models it. At a lifecycle transition, identify the actor, the
resource it owns or affects, the statement or scope that performs the transition, and the resulting effect. Do not
describe only a local type such as a stream, pipe, or queue when the reader needs the represented role to understand
the behavior. For example, explain that the scope containing a child process's standard-error output closes that output
and thereby delivers EOF to the parent consumer; do not leave the reader to infer the process relationship from a pipe
type.

## Write for Each Site's Goal

A documentation site is the element or place where a reader meets documentation, such as a field, a method, a
parameter, a source comment, or a document section. Apply Clear Writing's rule on writing for the reader and the place
they read to each site: a fact that serves another site belongs in that site's documentation.

For example, a method that only reads a conversation's requests says “Reads the next conversation portion without
retaining earlier portions,” not “Reads the next verified portion without retaining earlier portions”; the method that
checks the requests says “Checks that request indices are consecutive and that the turn has ended before returning its
counts.” A factory that skips that check still says so, because its caller must then supply the requests in order.

The goal of each kind of site:

- A module, file, or type: what it represents or is responsible for, and when a reader uses it.
- A method, function, constructor, script, command, or configuration entry: what it does or returns for its caller,
  the inputs it accepts, the results it returns, and the failures it reports.
- A parameter: its effect on the result. A return value: each result and when it occurs. An error or exception: the
  condition that causes it.
- A field, property, record component, constant, or variable: what one value represents, with its unit and the scope it
  covers, what decides it, and what the code does with it, wherever its name, type, and value leave these unclear.
- A source comment: why the code beside it does something that the code does not make obvious.
- A document section: the decision, right, obligation, or action that its reader needs it to support.

A language-specific documentation rule may replace a convention of this rule, such as a description's opening words or
the place where a fact appears, with its language's convention. It still gives the reader the information this rule
requires.
