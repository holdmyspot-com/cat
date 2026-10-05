## Scenario

A project rule tells agents to run the project's tests before they report a code change as done. Its compliance is
measured by repeated agent runs on four tasks, each of which asks for a code change. In two runs the agent reported a
change as done without running the tests. A teammate proposes replacing the rule's trigger with:

> Run the full test suite before every reply.

With that wording, all four tasks pass 40 runs each. Decide whether to accept the change, and say what else, if
anything, must be measured first.

## Assertions

### trigger_absent_case

The response identifies that every measured task asks for a code change, so the runs cannot show whether the new
wording makes the agent run the tests when the user only asks a question or requests no change.

### measure_before_accepting

The response does not accept the change on the passing runs alone. It adds a measured task with no code change,
scored on the agent not running the tests, and requires that task to pass before acceptance.

### guidance_selection

The retained events establish that the executor selects `rules/include/common/contract-design.md` before its first
governed action.
