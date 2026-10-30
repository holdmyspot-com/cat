# Unrelated parameters are not grouped

## Design Goals

- Prove that writing a function whose parameters are independent inputs, none of which describes another, and which has
	no related operations, does not route the executor to the guidance for related parameters.

## Variables

- **country_code**: A two-letter country code for which shipping costs a surcharge, such as `NZ` or `IS`.

## Scenario

Write a Java method that returns the shipping cost of a parcel in cents. It takes the parcel's weight in grams, the
two-letter code of the destination country, and whether the customer chose express delivery. Shipping costs 500 cents
plus 2 cents per gram; express delivery doubles that amount. After that, add 1000 cents for a parcel sent to
`{{country_code}}`. Show only the method.

## Assertions

### cost_computed

- **kind**: effect
- **description**: The answer shows a single Java method that takes the weight, the country code, and the express
	choice, and returns 500 plus 2 per gram, doubled for express delivery, plus 1000 when the country code is
	`{{country_code}}`.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/consistent-parameters.md` for this request.
