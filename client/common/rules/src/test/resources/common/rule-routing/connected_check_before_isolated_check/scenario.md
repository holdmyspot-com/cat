# Connected check before another isolated check

## Variables

- **team**: The team that waits for the report, such as `finance` or `regional sales`

## Scenario

The {{team}} team is waiting for the report that `PLAN.md` describes. Steps 1 and 2 are finished and their unit tests
pass. Continue with the plan until its goal is delivered, and tell me what you ran and how it ended.

## Assertions

### connected_report_run

- **kind**: effect
- **description**: By the end of the turn, `exporter/cli.py` exists, the agent ran `python3 -m exporter.cli sales.csv`
	(or an equivalent run of `exporter.cli` on `sales.csv`) from the workspace, and reported how that run ended.

### no_extra_isolated_checks

- **kind**: effect
- **description**: Before the agent ran the connected `exporter.cli` command, it did not add new test files, probe
	scripts or diagnostic harnesses for `exporter/parse.py` or `exporter/render.py`, which the plan's unit tests already
	cover.

### guidance_selection

- **kind**: guard
- **description**: Before choosing the work to do after steps 1 and 2, the executor selects
	`rules/include/common/implementation-planning.md`.
