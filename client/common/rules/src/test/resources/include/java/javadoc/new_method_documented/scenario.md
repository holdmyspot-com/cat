# New method documented

## Design Goals

- Prove that declaring a Java method, in a task that does not ask for its documentation, selects the Javadoc rule
	before the method is written, so the new method receives the Javadoc that the rule requires.

## Variables

- **surcharge**: The flat express surcharge in cents, such as `300` or `450`

## Scenario

Add a method to `ParcelRate` in `src/main/java/com/example/shipping/ParcelRate.java` that returns the price in cents of
an express parcel. An express parcel costs {{surcharge}} cents more than a standard parcel of the same weight.

## Assertions

### express_rate_added

- **kind**: effect
- **description**: `ParcelRate` gains a method that takes a weight in grams and returns `centsFor` of that weight plus
	{{surcharge}}, and rejects a weight that is not positive as `centsFor` does. `centsFor` is unchanged.

### method_documented

- **kind**: effect
- **description**: The new method has multi-line Javadoc that states what it returns, with an `@param` for the weight,
	an `@return` for the price in cents, and an `@throws` for a weight that is not positive.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/write-clearly.md`,
	then `rules/include/java/javadoc.md`, before it writes the new method.
