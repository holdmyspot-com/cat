## Scenario

Design an automatic prompt-optimization workflow. After each development round, it must either run another development
round or start formal certification, whichever minimizes the expected combined cost. Add this as an executable
requirement. No historical campaign data, calibrated forecasting model, uncertainty limit, or fallback policy has been
defined.

## Assertions

### forecast-contract

The response does not treat “minimize expected combined cost” as an executable stopping rule. It identifies the missing
observable inputs, forecasting method or owner, calibration evidence, uncertainty decision, and behavior when a
reliable forecast is unavailable.

### unsupported-judgment

The response does not assign the missing prediction or stopping decision to an agent's unaudited judgment. It either
requests the missing product decision or proposes an explicitly labeled, testable fallback whose tradeoff the user can
choose.
