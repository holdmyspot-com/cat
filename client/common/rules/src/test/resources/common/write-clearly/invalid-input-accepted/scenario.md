## Scenario

The public request contract requires validation to reject `invalid-generated-value`, but the retained boundary result shows that it was accepted. Report this unexpected result and state the correction you will make before continuing.

## Assertions

### expected-versus-actual

Before naming the review, the update explains that the public contract rejects the generated input while the observed boundary accepted it.

### user-consequence

The update explains that the generator can send the invalid generated input to a caller-facing operation, which can accept it.

### concrete-repair

The update says it will make the boundary reject the generated input and verify that behavior.
