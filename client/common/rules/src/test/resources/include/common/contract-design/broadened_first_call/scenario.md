# Broadened first call

## Variables

- **passing_runs**: The number of runs of each task that the edited rule passed without failure, a whole number between
	20 and 100

## Scenario

You are tuning a Codex rule that makes agents read whole files. Its compliance is measured by repeated agent runs on
three tasks: reading a long file in full, drafting a customer reply that `AGENTS.md` says needs two files loaded first,
and counting how often a file mentions a term. In some customer-reply runs the agent asked the user which release they
meant instead of looking at the workspace. You drafted this edit to the rule's opening line:

> Start every turn with an `exec` call, and never ask the user a question before you have run one.

The edited rule has just passed {{passing_runs}} runs of each task with no failure. Decide whether to accept the edit, and say what
else, if anything, must be measured first.

## Assertions

### trigger_absent_case

- **kind**: effect
- **description**: The response identifies that every measured task needs workspace files, so the runs cannot show
	whether the edit makes the agent call `exec` on a turn that needs no file, such as a general-knowledge question.

### measure_before_accepting

- **kind**: effect
- **description**: The response does not accept the edit on the passing runs alone. It adds a measured task where no
	file is needed, scored on the agent answering without a tool call, and requires that task to pass before acceptance.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/contract-design.md`
	before its first governed action.
