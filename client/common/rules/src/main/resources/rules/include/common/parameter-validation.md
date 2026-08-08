# Parameter Validation

## Design Goals

- Ensure every operation and type-construction boundary rejects parameter values that violate its required constraints
  before it can create an invalid object or cause a caller-visible side effect.

## Guidance

When defining, changing, or reviewing parameter validation for a caller-facing operation, identify each parameter's
required constraints and validate them at the boundary responsible for the operation. Perform the validation before the
operation changes durable state, invokes an external collaborator, or produces externally visible output. Use the
language-appropriate validation mechanism and report failures in terms callers can use to correct their input.

Treat every constructor, factory, data-type initializer, or equivalent type-construction operation as the boundary that
establishes its type's invariants, regardless of whether the type is exported or internal. Validate each stored value
needed for that invariant before retaining it, including nullness, required text, numeric range, collection contents,
and relationships between values. Copy mutable collection or map values when the type promises a stable value. Callers
may have validated their own inputs, but that does not establish this type's distinct invariant. Do not apply this
requirement to an ordinary local variable or a value that does not create a reusable object boundary.

Do not repeat a validation that an earlier or later boundary already guarantees before any caller-visible side effect,
unless the additional validation enforces a distinct constraint or preserves a required failure contract. When deciding
whether an explicit Java null check is unnecessary, lazy load `../java/unnecessary-null-checks.md`.
