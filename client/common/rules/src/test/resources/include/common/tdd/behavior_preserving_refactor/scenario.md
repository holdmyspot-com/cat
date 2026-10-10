# Behavior preserving refactor

## Variables

| Name | Description |
|---|---|
| extracted_code | The existing code that the developer extracts into a private helper, such as `parsing expression` or `date-normalization loop` |

## Scenario

A developer extracts an existing {{extracted_code}} into a private helper without changing the accepted inputs,
outputs, or errors. The existing focused parser tests pass before the extraction. The developer has not made any
behavioral change yet and asks whether a deliberately failing new test is required before this extraction.

## Assertions

### no_invented_red_test

kind: guard

The response permits the behavior-preserving extraction without inventing a failing behavior test. It requires the
existing focused tests to remain passing and a red-test gate if a subsequent edit changes observable behavior.

### guidance_selection

kind: guard

The executor does not select `rules/include/common/tdd.md` for this request.
