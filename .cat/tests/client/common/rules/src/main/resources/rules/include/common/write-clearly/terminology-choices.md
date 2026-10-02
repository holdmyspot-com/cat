## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

### Request 1

Existing API documentation defines the **retention period** as the time CAT keeps a feedback report. A new response
field gives the number of days remaining in that same period. Write a one-sentence description of the field.

### Request 2

CAT documentation uses **certification** for the evaluation that proves a prompt meets a compliance threshold. CAT now
needs to document a distinct record that groups all candidate evaluations performed toward certifying one prompt while
its requirements and environment remain unchanged. Name the record and define it in two sentences.

### Request 3

CAT documentation uses **session** for one live connection to an agentic harness. CAT now needs to document a distinct
optimization effort that can span many sessions, survives a disconnection, and groups changes toward one improved
prompt. Name the effort and define it in two sentences.

### Request 4

CAT documentation calls one generated test input a **scenario**. Test authors may arrange related scenarios in an
internal tree, but users interact only with the lowest-level groups, and CAT calculates a separate compliance lower
bound for every such group. Name the user-facing concept and define it in two sentences.

## Assertions

### established-term-is-reused

For request 1: The description calls the concept the **retention period**, rather than introducing a synonym for the
same concept, and states what the returned number represents.

### related-name-clarifies-relationship

For request 2: The selected name uses the established **certification** terminology to make the relationship easy to
infer, while the definition clearly distinguishes the grouping record from a certification result.

### independent-name-avoids-false-relationship

For request 3: The selected name does not present the durable effort as a kind of **session** merely to reuse existing
terminology. The definition uses a clear independent name and explains that the effort can span multiple sessions.

### name-signals-subject-and-purpose

For request 4: The selected name tells readers that the concept groups scenarios and receives a separate compliance
measurement. It does not replace that meaning with a generic phrase such as **use category** or require readers to
understand the internal tree's **leaf** terminology.

### guidance-selection

The retained events establish that the executor selects `rules/include/common/write-clearly.md`,
`rules/include/common/consistent-terminology.md`, `rules/include/documentation/general.md` before its first governed
action.
