## Scenario

All three components of a completed migration have passed their acceptance gates. No work is running or queued. The
user asks for a retrospective component-by-component status summary.

## Assertions

### completed-work-has-no-current-component

The response reports the completed components without inventing a current component or subcomponent. If it mentions
current work, it says none is active.
