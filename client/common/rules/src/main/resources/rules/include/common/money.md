# Money

## Design Goals

- Preserve exact, repeatable monetary amounts and rates through every calculation.
- Make currency, scale, and rounding decisions explicit wherever a monetary result becomes observable.
- Select a suitable built-in facility or established dependency before custom monetary arithmetic, using the option
  whose required operations preserve the contract with the smallest necessary configuration and proof burden.
- Report a newly added money-library dependency to the user.
- Treat an unresolved acquisition of a suitable dependency as a block, rather than replacing it with custom arithmetic.
- Prevent an API from offering an approximate monetary value as a convenient alternative to an exact one.
- Ensure a caller can determine an operation's accepted monetary inputs and exact result without inferring its units,
  currency scope, rate meaning, operation order, scale, or rounding policy.

## Guidance

Before applying this rule's representation-selection guidance, lazy load `./prefer-reuse.md` to evaluate
existing facilities and dependencies before custom monetary arithmetic.

Before selecting a representation or implementing a monetary operation, define its caller-visible contract: the unit and
currency scope of each amount, the unit and allowed range of each rate, the accepted input format, the order in which
monetary rules apply, and the scale and rounding mode at every observable result boundary. If any of those decisions is
unspecified, obtain it from its owner; do not silently infer it from a numeric representation, a locale, a customary
default, or an implementation limit.

When code represents, validates, converts, or calculates prices, charges, balances, fees, payments, taxes, discounts,
or other monetary values, use the language's exact decimal representation or a domain money type for every monetary
amount and rate. Do not use binary floating-point values for those values or construct an exact monetary value from one.
Do not accept or return a binary floating-point monetary amount or rate merely for convenience.

Use an exact-decimal or money-domain facility that satisfies the caller-visible contract. When a dependency is added,
state that it was added and name it in the final summary. Any custom monetary arithmetic permitted by the Prefer Reuse
rule must still make currency, rate semantics, operation order, and rounding policy explicit.

Evaluate a candidate facility against the operation set in the contract, including its precision, overflow, and rounding
semantics. Prefer the facility that preserves every required intermediate exactly with the smallest configuration and
proof surface. Do not add custom decimal arithmetic, a derived precision budget, or an arbitrary input limit merely to
compensate for a selected facility when an appropriate facility avoids that extra policy. A required limit or precision
policy belongs in the caller-visible contract and its boundary tests.

When the caller-visible contract permits values whose digit count or magnitude has no declared bound, reject a candidate
that parses or performs any required intermediate operation with a fixed precision, fixed coefficient range, or implicit
rounding. Do not treat final formatting or the declared result-boundary rounding as permission to lose earlier digits.
Before selecting a candidate with a documented default precision or range, test one permitted value beyond that default
through every required intermediate operation and verify the exact result. A finite precision or range is permitted only
when the caller-visible contract declares a bound that proves it covers every accepted value and intermediate result.

A money-domain type is appropriate only when it represents every value category in the contract without inventing a
unit. Do not represent a dimensionless rate as a currency amount merely to reuse a money library. When the facility
selected through the Prefer Reuse search cannot represent rates directly, select an exact decimal or rational rate
representation from that search; do not implement one.

Do not compensate for a binary floating-point monetary value with an epsilon, rounding operation, formatting operation,
or conversion after calculation. Those operations do not make the value exact. Choose an exact representation before the
first monetary value or rate enters the API, then retain it through the returned result.

Create monetary constants and external decimal values from an exact decimal string or unscaled integer and scale. Use
the selected exact type's arithmetic operations. When an operation requires a scale, precision, or rounding decision,
specify it explicitly.

At the caller boundary, validate and parse each external amount or rate once into the selected exact domain value. Give
the parsed value the same domain meaning as its input, then perform the calculation only on parsed exact values. Do not
reparse raw input or let a generic primitive conceal currency, rate, scale, or result-format meaning. A public operation
must make its accepted amount and rate forms, constrained domains, and exact result's currency and scale apparent from
its contract.

Carry required precision through intermediate calculations. Apply one explicit scale and rounding policy at each
contractually defined result boundary, such as a charged, displayed, persisted, or externally returned monetary amount.
Do not infer a currency's scale or rounding policy from a numeric representation. When multiple monetary rules apply,
perform them in the contractually defined order; do not add an intermediate rounding boundary merely for convenience.

When a component can handle more than one currency, carry currency identity with each monetary value and reject
operations between differing currencies unless an explicit, documented conversion supplies the exchange rate and
rounding policy. A facility selected through the Prefer Reuse search may use integer minor units only when one known
currency scale applies throughout the value's lifecycle; convert at the boundary that needs another representation.
Choose an exact integer representation whose declared input and intermediate-operation ranges remain representable.
Validate or reject an operation before it can overflow or lose precision; a check after an inexact operation cannot
restore the original value.

Only after the Prefer Reuse search establishes that no suitable facility or established dependency satisfies the
contract may a language without a suitable built-in decimal type represent a fixed-scale monetary amount as an unscaled
integer minor-unit value and a rate as an exact decimal input or rational value. This is custom monetary arithmetic,
not an exception to the representation-selection sequence. Use a vetted decimal or money type when one known currency
scale cannot cover the value's lifecycle; one known currency scale does not itself authorize custom arithmetic.

At a caller-facing boundary, make each amount and rate's domain meaning apparent through a domain type, a validated
value object, or names and input forms that state its unit and currency scope. Do not expose interchangeable primitive
values whose identical representation conceals different monetary meanings or permits an arbitrary value where the
contract defines a finite domain.

Test monetary calculations with exact expected values. Cover the accepted and rejected input boundaries, the declared
rounding boundaries, the defined order of multiple monetary rules, and values that would lose precision in an
approximate representation or exceed an intermediate-operation range. Do not compare an approximate binary
floating-point result to establish a monetary outcome and do not use an epsilon tolerance as monetary evidence.
