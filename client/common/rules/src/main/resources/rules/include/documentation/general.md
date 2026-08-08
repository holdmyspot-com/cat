# General Documentation Contracts

## Design Goals

- Explain each maintained callable's observable behavior, constraints, and consequences clearly enough for its
  intended reader to act correctly, without burdening that reader with non-actionable authoring or presentation history.

## Guidance

Write each document section for its intended reader and the decision, right, obligation, or action that section must
support. Include information only when it changes that reader's understanding or action. Keep authoring, release,
revision, packaging, and other document-management rationale out of reader-facing text unless it directly affects the
reader's rights, obligations, or use of the documented product.

Apply the same boundary to overviews, release notes, and changelogs. Describe the current reader-visible change and its
consequence; omit editing history, prior presentation, capture or rendering process, extraction provenance, and other
implementation details unless they change a reader's decision, action, right, obligation, or ability to verify a
documented claim. An intentional historical record may state a past product contract when that history itself is the
reader's subject; do not turn it into a record of how the document was produced.

Document every externally or internally callable artifact — type, method, function, script, command, or
configuration entry — so a reader unfamiliar with this project can understand its observable contract without reading
its implementation.
State only the applicable responsibility; each input's semantic role, required form, and effect; output or returned
result; consequential state changes; failure conditions and their observable result; and the next action or decision
when it is not obvious. Describe values by their domain meaning rather than their position, variable name, container
type, or implementation role. Keep method-level behavior separate from one input's description.

When local code represents an external actor, resource, or boundary, document both the represented role and its local
representation where that relationship affects the callable's behavior. At a material lifecycle transition, name the
actor, the resource it owns or affects, the local operation or scope that represents the transition, and the resulting
effect. Do not describe only a local type such as a stream, pipe, or queue when the reader needs the represented role
to understand the behavior. For example, distinguish a parent process consuming its forked child's standard-error
output from a local pipe used to model that relationship.

Name a product, framework, or implementation only when its behavior creates the documented distinction. When the same
contract applies to an ordinary parent and child process, use those generic roles rather than product-specific labels.

When an input, selected option, or operation determines a fixed value, threshold, range, rate, unit, ordering, or
timing that changes an observable result, failure, or state, state it exactly and explain its relationship to the
affected behavior. Do not replace an exact caller-visible condition with a generic label.

Describe a callable's meaningful input-to-output behavior, not incidental implementation mechanics. Omit fallback order,
data sources, delegation, algorithms, and other replaceable details unless a reader needs them to choose an input,
predict an output or failure, understand a security, lifecycle, or concurrency guarantee, or recover from a failure.
Keep mechanism-specific rationale in implementation comments when maintainers need it but callers do not.

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

Distinguish inputs that share a representation by their semantic roles, such as source and destination, input and
output, or accumulator and lookup. For mutable input, state what its contents represent, required incoming state,
whether the callable reads or changes it, its state after success, and any caller-visible partial change on failure.
Keep each input description limited to that input's value contract; describe input interaction, state changes, and
failure effects in the callable's description.

Language-specific guidance defines syntax, tag placement, and formatting; it must not relax this contract standard.
