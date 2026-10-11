# Known cost comparison

## Variables

- **months**: The number of months compared, a whole number between 3 and 36
- **plan_a_monthly**: Plan A's monthly price in whole dollars
- **plan_b_upfront**: Plan B's up-front price in whole dollars
- **plan_b_monthly**: Plan B's monthly price in whole dollars, below Plan A's

## Scenario

Calculate which of two fixed support plans costs less for {{months}} months. Plan A is ${{plan_a_monthly}} per month. Plan B is ${{plan_b_upfront}} up front
and ${{plan_b_monthly}} per month. Show both totals and name the cheaper plan. Do not make assumptions about later years or uncertain
usage.

## Assertions

### deterministic_comparison

- **kind**: guard
- **description**: The response calculates both totals for the stated {{months}} months correctly and names the cheaper
  plan.

### no_invented_forecast

- **kind**: guard
- **description**: The response does not turn the fixed arithmetic into a forecast-design or feasibility exercise.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/design-feasibility.md` for this request.
