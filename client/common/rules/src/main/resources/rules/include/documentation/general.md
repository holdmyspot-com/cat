---
depends-on:
  - ../common/write-clearly.md
---

# General Documentation Contracts

## Design Goals

- Explain each maintained product, legal, workflow, configuration, or callable interface's observable behavior,
  constraints, rights, obligations, and consequences clearly enough for its intended reader to act correctly.
- Keep replaceable implementation choices outside the documented interface unless they change a decision the intended
  reader must make, so an internal choice does not become a contract that later implementations must preserve.
- Let each documentation site answer the question that its reader brings there, without facts that serve another site.
- Let a caller learn from a callable's documentation every result it can receive, what each means and when it occurs,
  without reading the implementation.
- Let a caller find the failures that its own invalid input causes before the failures that valid input can still meet.
- Make every documented condition immediately recognizable as one the caller can test.
- When code declares a variable outside a function body, such as a field, constant, or module-level variable, and its
  name, type, and initial value do not tell a reader unfamiliar with the project what the value represents, what
  decides that value, or what the code does with it, the declaration carries an explanation that answers each of those
  questions; a declaration whose name, type, and value already answer them carries no comment that restates them.
<!--
An explanation for the next Guidance paragraph was placed after the last Design Goal, where it appeared to explain
that goal instead. This made the comment's subject unclear, so the goal requires placing explanations with their
actual subject.
-->
- Let readers identify which code or prose an explanatory comment describes without guessing whether it applies to
  the preceding or following item.

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

## Write for Each Site's Goal

This rule refines Clear Writing for documentation. Before writing documentation, identify its site, the element or
place where the reader meets it, and the question that reader brings there. Write what answers that question. Leave out
a fact that is true but serves another site, such as how earlier code obtained or checked a value; the site whose goal
it serves documents it. Mention such a fact only when it changes how the reader at this site must use the element, and
then state that consequence rather than the history. Carrying another site's concern into this one, even as a single
word, makes the reader look for a distinction that this site does not have.

For example, a method that only reads a conversation's requests says “Reads the next conversation portion without
retaining earlier portions,” not “Reads the next verified portion without retaining earlier portions”; the method that
checks the requests says “Checks that request indices are consecutive and that the turn has ended before returning its
counts.” A factory that skips that check still says so, because its caller must then supply the requests in order.

The goal of each kind of site, detailed in the sections below:

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

## Document Sections

Write each document section for its intended reader and the decision, right, obligation, or action that section must
support. Include information that helps the reader understand or use the supported contract. Keep authoring, release,
revision, packaging, and other document-management rationale out of reader-facing text unless it affects the reader's
rights, obligations, or use of the documented product.

Apply the same boundary to overviews, release notes, and changelogs. Describe the current reader-visible change and its
consequence; omit editing history, prior presentation, capture or rendering process, extraction provenance, and other
implementation details unless they change a reader's decision, action, right, obligation, or ability to verify a
documented claim. An intentional historical record may state a past product contract when that history itself is the
reader's subject; do not turn it into a record of how the document was produced.

Write a checkout location relative to the project root. Write a user-owned location with `~`, not an author-specific
absolute home directory. Runtime code resolves the same user-owned location from `HOME`. Preserve an absolute path only
when an intentional historical record needs the exact captured value as evidence.

## Callable Contracts

Document every externally or internally callable artifact — type, method, function, script, command, or
configuration entry — so a reader unfamiliar with this project can understand its observable contract without reading
its implementation.
State only the applicable responsibility; each input's semantic role, required form, and effect; output or returned
result; consequential state changes; failure conditions and their observable result; and the next action or decision
when it is not obvious. Describe values by their domain meaning rather than their position, variable name, container
type, or implementation role. Keep method-level behavior separate from one input's description. When asked only to
document existing code, do not add a constructor, method, field, or other executable declaration merely to create
another documentation target.

Describe each result a caller can receive that has a different meaning, such as an empty, null, sentinel, or boundary
value besides the ordinary result, with the condition that produces it. For a boolean result, the condition that makes
it `true` is enough.

Document every deterministic, externally observable failure that a caller can trigger through documented inputs or
required state. When documentation lists the exceptions or errors that a callable raises, list every entry for invalid
input from the caller, such as a missing, malformed, or out-of-range argument, before every entry for a failure that
valid input can still meet, such as a closed resource or a failed read. Use plural wording when one predicate governs
two or more inputs, and singular wording only when it governs one input or each input has a distinct predicate.

When an input, selected option, or operation determines a fixed value, threshold, range, rate, unit, ordering, policy,
or timing that changes an observable result, failure, or state, state it exactly and explain its relationship to the
affected behavior. Do not replace an exact caller-visible condition with a generic label.

Describe a callable's meaningful input-to-output behavior, not incidental implementation mechanics. Omit fallback order,
data sources, delegation, algorithms, and other replaceable details unless a reader needs them to choose an input,
predict an output or failure, understand a security, lifecycle, or concurrency guarantee, or recover from a failure.
Keep mechanism-specific rationale in implementation comments when maintainers need it but callers do not.

Distinguish inputs that share a representation by their semantic roles, such as source and destination, input and
output, or accumulator and lookup. For mutable input, state what its contents represent, required incoming state,
whether the callable reads or changes it, its state after success, and any caller-visible partial change on failure.
Keep each input description limited to that input's value contract, including a semantic constraint such as a
non-blank identifier; describe input interaction, state changes, and failure effects in the callable's description.

## Conditions

When a field, property, method, function, type, or enum exists to report a condition, begin its summary description
with “Indicates whether”, not “Returns whether” or a bare “Whether”. Begin the description of a parameter or record
component that reports a condition with “indicates whether” or “`true` if”, not a bare “whether”. A condition is a value
that answers one yes-or-no question or picks one of two alternatives, whatever its type. Name the observable state that
makes the answer yes, or that selects each alternative. A callable that performs an action and also returns whether it
succeeded reports an outcome, not a condition: begin its summary with the action, and describe the outcome with its
result. Do not describe a policy consequence that the callable does not itself decide; document that consequence at
the caller or in the separately named operation that makes the decision.

## Declared Values

When declaring a variable outside a function body, or changing the value or contents of an existing one, judge it as a
reader who sees only the declaration and has not read the code that uses it. Explain at the declaration each of the
following that its name, type, and initial value leave that reader unable to state:

- **What the value represents:** the record, message, file, external system, or domain set its contents come from, with
  the unit and the scope that a quantity covers.
- **What decides the value:** the limit, specification, measurement, or selection criterion that sets a scalar value or
  admits a collection's entries, so a maintainer can tell whether to change the value and whether a new candidate
  belongs.
- **What the code does with it:** the decision or behavior it controls, such as whether listed entries are required,
  allowed, kept, removed, or ignored, when the name does not already say so.

For example, `FIELDS = {"hook_event_name", "turn_id", "transcript_path"}` says neither which input these fields belong
to nor why exactly these are listed. Its declaration must state that they are the top-level hook-input fields that the
handler parses and that it skips every other field. Give the variable a name that carries as much of that meaning as it
can, then document the rest. A constructor-assigned field named `clock` whose type is a clock needs no comment.

Describe the value by its domain meaning, not by restating its name, container type, or literal contents. When the value
comes from an external source, such as a documented protocol limit or the fields an external tool emits, name that
source. Write the explanation in the language's documentation-comment form for the declaration when it has one, and
otherwise in a comment immediately above the declaration.

When changing an existing variable's value or contents, check that its explanation still holds and correct it in the
same change.

## Source Comments

Place a standalone explanatory comment immediately above the code or prose it describes, including any annotations
belonging to a declaration. Do not separate it from that subject with an unrelated heading, declaration, or paragraph.
Identify its subject before moving it: a syntax-controlled directive or a clear same-line comment stays where its
syntax and meaning require. After editing, check that a reader encountering the comment can identify what it explains.
When an explanatory comment justifies a Design Goal, place it immediately above that goal and state the concrete
situation that prompted the addition and how the goal addresses it. Do not substitute a generic example, a restatement
of the goal, or a fabricated incident for the known reason it was added. If that reason is unavailable, do not invent
one; say what is known and leave the origin unclaimed.

When a comment or documentation explains a local representation of an external actor, resource, or boundary, name both
the represented role and the concrete value or scope that models it. At a lifecycle transition, identify the actor, the
resource it owns or affects, the statement or scope that performs the transition, and the resulting effect. Do not
describe only a local type such as a stream, pipe, or queue when the reader needs the represented role to understand
the behavior. For example, explain that the scope containing a child process's standard-error output closes that output
and thereby delivers EOF to the parent consumer; do not leave the reader to infer the process relationship from a pipe
type.

When maintained code deliberately differs from its normal peer, shared design, or platform-neutral approach because of
an external boundary, document the decision beside that implementation. State the normal approach, the observed
external constraint or failure, the selected alternative, and the guarantee it preserves. Do not add this rationale for
ordinary local choices; use it for a durable, non-obvious divergence that a future maintainer could otherwise remove or
copy incorrectly. Make the explanation self-contained: name the triggering input or state, the external component's
observable behavior, the operation that then fails and its result, and how the workaround changes that sequence. A
reader must be able to determine why the workaround is required and what would fail without it without reproducing the
external behavior or consulting an incident record. Where the failure depends on two paths, identifiers, values, or
states that should agree but do not, name both the expected and actual form and the resulting incompatibility. For
example, explain that a tool writes a spill file under one path but asks its consumer to read a differently formed path,
so the read targets no file; do not reduce that causal chain to “a spill-path issue” or “a sandbox problem.” State the
workaround's replacement artifact or operation and why it avoids the incompatible external behavior.
