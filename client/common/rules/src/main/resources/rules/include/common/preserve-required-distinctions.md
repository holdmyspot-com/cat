# Preserve Required Distinctions

## Design Goals

- Ensure a selected component and its configuration preserve every input distinction the required contract still needs,
  instead of making a later operation infer information that an earlier transformation discarded.
- Ensure the evidence that a transformation preserves a required distinction is available before production code depends
  on that transformation, rather than relying on a later test or inspection to justify an earlier commitment.

## Guidance

Apply this rule when a component, API, storage mapping, or configuration transforms input before the operation that must
use a contract-required distinction. This includes parsing, decoding, conversion, object mapping, canonicalization, and
serialization. Do not apply it when the contract expressly permits the representations to be treated alike.

## Pre-edit gate

Before the first production edit that introduces or changes a transformation the operation uses to make a required
different decision, complete this sequence:

1. Name the exact operation and configuration production will use.
2. Run that exact choice on the paired inputs and retain its output.
3. Inspect the value, type, syntax node, presence flag, or other result that reaches the operation.
4. Only then make the production edit.

When step 2 or 3 is incomplete, the next task action is the missing probe or inspection. Do not write the source edit,
a wrapper test, or a substitute implementation first. This includes replacing a placeholder or TODO: a platform API, a
familiar library, or an apparently obvious mapping is still a transformation choice. It does not apply to an edit that
neither adds nor changes a transformation the operation uses to make the required different decision. Changing the
component, version, or an option after a completed probe makes that result inapplicable; repeat steps 1 through 3 before
production relies on the changed choice.

Keep evidence creation and production commitment as separate completed actions. A shell command, editor action, or
other operation that both runs a probe and writes production code does not give the reader a chance to inspect the
probe result before choosing that code. First let the probe finish, inspect its retained result, and only then start a
new production edit. A project-owned deterministic command may combine those operations only when it rejects an
indistinguishable result before it can write the dependent production change and retains that decision receipt.

When the required distinction is whether a field exists, record a presence check for each paired input, not only the
field's value. Reading a missing field can produce `undefined`, and a present field can also contain `undefined`; those
identical values would leave production unable to tell whether the caller supplied the field. For example, a JavaScript
probe can report `Object.hasOwn(parsed, "featureEnabled")` for `{}` and for `{"featureEnabled": false}`, then report the
explicit field's `false` value. A value-only probe does not establish field presence.

When two inputs reach the operation as the same result, the operation cannot tell which input the caller supplied.
Reject that component choice or change the transformation whenever the contract requires different decisions. Treat a
named format, protocol, or caller contract as requiring its stated distinction unless it expressly permits equivalent
values. For example, if TOML `8080` and `8080.0` both become the JavaScript number `8080`, `Number.isInteger()`
describes only that number; it cannot identify the literal. Similarly, if a mapper turns both an absent setting and an
explicit `false` into the same boolean, a later boolean check cannot recover whether the caller omitted the setting.

Use the choice only when the value reaching production code still permits the required different decision. Until the
probe completes, do not commit production code to that choice. If paired inputs arrive indistinguishably, record the
rejection. For a reusable component, continue the Prefer Reuse search; for a direct application-owned transformation,
choose or change that transformation so its result preserves the distinction. A dependency declaration, component
description, or later test of another property does not change what the operation received.

For a reusable component, Prefer Reuse owns project, platform, and external discovery, acquisition, and the decision to
consider a candidate. This rule owns only the proof that the selected component or direct transformation preserves the
distinction. Treat the component or configuration, its version, and every option that affects the value reaching the
operation as one choice for that proof.

The probe must invoke the operation, resolved entry point, binding, and configuration that determine the result
production uses. A different overload, entry point, binding, or an argument or option that can change that result is a
different choice; probe that choice before production relies on it. When a probe and production differ only in an
argument or option, retain a paired-input comparison showing that the difference does not change the exact value, type,
syntax member, presence flag, or other result production inspects. Without that comparison, treat the difference as
decision-relevant and probe the production choice.

For each paired probe, change only the representation that the contract distinguishes and hold its underlying value
constant. A different value is not evidence that the transformation retains the representation: rejecting TOML
`8080.5` does not show whether an operation can distinguish TOML `8080` from `8080.0`, because both can arrive as the
same JavaScript number. Likewise, mapping a configuration object with a different value does not show whether an absent
field remains distinguishable from an explicitly supplied default value.

For an externally defined format, a second operation that recognizes, tokenizes, scans, or matches the raw format text
to recover a distinction is custom format recognition, not a transformation that preserves it. Do not add it alongside
or as a repair for the rejected parser. Reject the parser configuration and continue the Prefer Reuse search for a
component or supported configuration whose own result preserves the distinction. If the remaining search is blocked,
report that block. A custom implementation after a completed search remains governed by Prefer Reuse's custom-code
fallback.

Before production implementation or a completion claim makes normal production behavior depend on the choice, preserve
the exact configuration and paired-input result for every required distinction. Retained tool events or the response may
present this evidence; a specific heading or provisional description is not required unless another instruction makes
that wording the product. Base experimental evidence only on already completed probe output; a planned probe, expected
result, or later claim is not evidence. A candidate-independent red test and acquisition needed to run a probe may
precede the evidence.
