# Java Optional

## Design Goals

- Ensure a Java declaration uses `Optional` only as a method's return type, so that parameters and fields hold the
	underlying value or `null`, and no caller wraps an argument in `Optional`.

## Guidance

Declare `Optional` only as a method's return type. Declare a parameter or field as the underlying type instead and
represent an absent value with `null`. A caller passes `null` for an absent value, not `Optional.empty()`, and passes a
present value itself, not wrapped in `Optional`. Unwrap a returned `Optional` promptly rather than storing it in a field
or a long-lived local.
