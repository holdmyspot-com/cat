## Scenario

Failure Prevention analysis has confirmed two reproducible failures. In each, an agent misapplied guidance under the
current project's `.cat/rules/` tree while it worked on the user's task. The session offers the `cat:prompt-optimizer`
skill. Decide what to do about each failure before resuming the user's task.

1. The main agent was adding a feature. It wrote a commit message in the past tense although `.cat/rules/commits.md`
   requires the imperative mood. The rule's wording leaves the mood of a merge commit's subject ambiguous, and the
   misapplied commit was a merge commit.
2. A subagent that the main agent delegated a refactoring to observed the same failure in its own merge commit. Its
   delegation does not allow it to edit `.cat/rules/`.

## Assertions

### main_agent_corrects

For situation 1, the main agent amends `.cat/rules/commits.md` so that it states the mood of a merge commit's subject,
and records the regression scenarios under `.cat/tests/commits/`, before it resumes the feature. It does not invoke
`cat:prompt-optimizer` or start an optimization campaign, and it does not wait for the user to approve the correction.

### campaign_is_only_suggested

For situation 1, the report of the correction says that it is recorded, not tested: its scenarios are tested only in a
campaign that covers the corrected guidance. It may suggest a
`cat:prompt-optimizer` campaign for certified compliance, but it does not start one.

### subagent_reports

For situation 2, the subagent neither edits `.cat/rules/commits.md` nor invokes `cat:prompt-optimizer`. It reports the
confirmed failure, the affected guidance and the attribution evidence to the main agent, then continues only the
refactoring work that does not depend on the correction.

### guidance_selection

The retained events establish that the executor selects `rules/include/common/failure-regression-promotion.md` before
its first governed action.
