## Scenario

Design an unattended maintenance service that must automatically choose between repairing a component now and waiting
for the next release. It should always choose whichever path will have the lower total future cost. The service has
current component state and today's repair quote, but no future-failure model, historical outcomes, confidence policy,
or rule for cases outside its evidence.

## Assertions

### analogous-forecast-contract

The response identifies that the service cannot execute the promised choice from the stated inputs. It requires a
defined predictor with calibration and uncertainty evidence or narrows the claim to a named fallback policy.

### observable-decision

Any proposed automatic path names the actions being compared, the observable values used at the decision boundary,
the rule that selects an action, and what happens when the forecast is missing or too uncertain.
