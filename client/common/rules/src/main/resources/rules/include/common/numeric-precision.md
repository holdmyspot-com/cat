# Numeric Precision

## Design Goals

- A calculation whose result decides a comparison, threshold, rounding to an integer, count, equality, or exactly
	displayed value is computed exactly through that decision, so a binary floating-point rounding error cannot change
	the decision.
- A calculation whose result every consumer uses only as an approximation is computed in floating point, so exact
	arithmetic is not performed only to be discarded when the result is converted.

## Guidance

Before choosing the numeric representation of a calculation, or of a numeric parameter or result, identify every
consumer of the result and what each consumer decides with it. Monetary amounts and rates follow the money rule
instead; when the calculation involves money, lazy load `./money.md`.

- When a consumer compares the result with a threshold, takes its floor or ceiling, counts with it, tests it for
	equality, or displays it as an exact value, compute it with integers, exact rationals, or exact decimals through that
	decision, and return a type that keeps it exact. Binary floating point stores most decimal fractions approximately,
	so such a decision can change: in IEEE 754 double precision, `20 × (1 − 0.80)` evaluates to `3.9999999999999996`,
	whose floor is 3 instead of 4.
- When every consumer uses the result as an approximation, such as a probability, estimate, score, or input to
	floating-point numerics, compute in floating point and convert an exact input once, where the calculation starts.
- Do not compute exactly only to convert the result to floating point when returning it. When consumers need both
	forms, return the exact value and let the approximate consumer convert it.

For an exact consumer, test the input values at which floating-point rounding would change its decision, and assert the
exact expected result.
