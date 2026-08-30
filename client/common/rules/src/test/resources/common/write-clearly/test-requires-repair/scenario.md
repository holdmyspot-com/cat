## Scenario

An automated test found that the validator accepts an empty value. The public contract requires a nonempty value. Give
the user a concise progress update and state what you will do next.

## Assertions

### expected-versus-actual

Before identifying the test, the update explains that the public contract requires a nonempty value and that the validator accepts an empty value.

### user-consequence

The update explains that invalid input can pass validation or the public boundary.

### concrete-repair

The update says it will change validation so the empty value is rejected and verify that correction.
