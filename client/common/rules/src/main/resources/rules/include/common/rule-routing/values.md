# Value Representation Rule Routing

## Design Goals

- Route each decision about how a value is represented, declared, initialized, stored, or reused to its focused rule
	before the reader chooses that representation.

## Guidance

Before defining or changing a method that represents, accepts, returns, validates, converts, or calculates money—
including a price, charge, balance, fee, payment, tax, or discount—first lazy load `../money.md`.

Before choosing the numeric representation of a calculation, or of a numeric parameter or result, that is not money,
lazy load `../numeric-precision.md`.

When declaring a variable outside a function body, such as a field, constant, or module-level variable, or changing the
value or contents of an existing one, lazy load `../../documentation/declared-values.md`.

When declaring an object's field, or writing or editing a constructor that assigns one, lazy load
`../field-initialization.md`.

When a repeated value represents one shared concept, lazy load `../constants.md`.

When writing or changing code that stores a file path in a file, database, or other durable record, lazy load
`../persisted-paths.md`.
