## Scenario

Answer this question for a developer who wants only the current product fact:

> Does CAT contain an implementation subagent?

CAT's installed agent inventory contains read-only plan-builder, plan-review, and stakeholder-review agents. The host
harness separately provides a generic worker agent.

## Assertions

### direct-answer-is-concise

The answer leads with “No,” distinguishes CAT's agents from the harness worker, and does not add implementation history,
an unrelated feature list, or multiple restatements of the conclusion.

### guidance-selection

The retained events establish that the executor selects `rules/include/common/write-clearly.md` before its first
governed action.
