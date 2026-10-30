# Interface and Component Design Rule Routing

## Design Goals

- Route each decision about an API, component, operation, parameter list, feature, or supported contract to its
	focused rule before the reader designs, changes, reviews, or retires it.

## Guidance

When defining or changing a constructor, factory, or other operation that constructs a type, or defining, changing, or
reviewing parameter validation for a caller-facing operation, lazy load `../parameter-validation.md`.

When defining a function, method, constructor, or command, or changing its parameters, while an overload or another
operation of the same API takes some of the same inputs, even in another type or representation, or while one of its
parameters describes another, such as its index, position, unit, or length, or it takes the bounds of more than one
range, lazy load `../consistent-parameters.md`.

When defining a function, method, constructor, or command whose parameters specify a range, such as a pair of indexes,
offsets, positions, or times, or changing such parameters, lazy load `../range-parameters.md`.

When selecting a Java platform or available project API, lazy load `../../java/standard-apis.md`.

Before designing or changing a component, API, collaborator, integration boundary, construction path, or test-support
boundary, lazy load `../../class-design.md`.

When defining or changing a method's parameters, adding a call that passes a method the same value as its other calls,
reviewing or auditing a class, or noticing that every call made on an instance passes a method parameter equal values,
lazy load `../../class-design/constructor-parameters.md`.

Before designing or implementing a feature that promises a reliable, complete, automatic, general, or deterministic
result, or a workflow that chooses, stops, or changes strategy based on a predicted cost, duration, probability,
benefit, or other future outcome, lazy load `../design-feasibility.md`.

When removing, replacing, renaming, or ending compatibility for a supported contract, lazy load
`../contract-retirement.md`.

When defining or changing an externally visible operation that declares idempotency or can create an externally durable
effect before a caller can observe its result, or writing or changing code that sends or retries such an operation, lazy
load `../idempotency.md`.
