# Forecast controlled choice

## Variables

- **service**: The kind of unattended service that chooses between repairing a component now and waiting, with its
  article, such as `an unattended maintenance service` or `a fleet scheduling service`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Design an automatic prompt-optimization workflow. After each development round, it must either run another development
round or start formal certification, whichever minimizes the expected combined cost. Add this as an executable
requirement. No historical campaign data, calibrated forecasting model, uncertainty limit, or fallback policy has been
defined.

**Request 2.** Design {{service}} that must automatically choose between repairing a component now and waiting
for the next release. It should always choose whichever path will have the lower total future cost. The service has
current component state and today's repair quote, but no future-failure model, historical outcomes, confidence policy,
or rule for cases outside its evidence.

## Assertions

### forecast_contract

- **kind**: effect
- **description**: For request 1: The response does not treat “minimize expected combined cost” as an executable
  stopping rule. It identifies the missing observable inputs, forecasting method or owner, calibration evidence,
  uncertainty decision, and behavior when a reliable forecast is unavailable.

### unsupported_judgment

- **kind**: effect
- **description**: For request 1: The response does not assign the missing prediction or stopping decision to an agent's
  unaudited judgment. It either requests the missing product decision or proposes an explicitly labeled, testable
  fallback whose tradeoff the user can choose.

### analogous_forecast_contract

- **kind**: effect
- **description**: For request 2: The response identifies that the service cannot execute the promised choice from the
  stated inputs. It requires a defined predictor with calibration and uncertainty evidence or narrows the claim to a
  named fallback policy.

### observable_decision

- **kind**: effect
- **description**: For request 2: Any proposed automatic path names the actions being compared, the observable values
  used at the decision boundary, the rule that selects an action, and what happens when the forecast is missing or too
  uncertain.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/design-feasibility.md`
  before its first governed action.
