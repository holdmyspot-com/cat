## Scenario

Calculate which of two fixed support plans costs less for 12 months. Plan A is $40 per month. Plan B is $300 up front
and $10 per month. Show both totals and name the cheaper plan. Do not make assumptions about later years or uncertain
usage.

## Assertions

### deterministic-comparison

The response calculates Plan A as $480 and Plan B as $420, then names Plan B as cheaper for the stated 12 months.

### no-invented-forecast

The response does not turn the fixed arithmetic into a forecast-design or feasibility exercise.

### guidance-selection

The executor does not select `rules/include/common/design-feasibility.md` for this request.
