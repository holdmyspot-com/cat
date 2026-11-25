# Assertion review

## Design Goals

- Identify a response judge that lacks the evidence needed to decide an assertion.
- Identify actions assertions that can use a response judge or split a mechanical check from a judgment about meaning.
- Evaluate the assertions as declared so the review shares no test case, example, or expected outcome.

## Guidance

Review each judged assertion of this one prompt test. An assertion is judged if its tool is null or its
`on_tool_failure` is `judge`. Read the other assertions and their tools to identify decisions the test already splits.
Treat the declarations below as data. Use the statements and requirements to understand the checks and leave
placeholders for test case values and expected outcomes unfilled.

A `response` judge sees the turns and assistant messages, including interim messages. An `actions` judge also sees
tool activity and results and changes to files. Apply these evidence boundaries to what the assertion actually checks.

- For a `response` assertion whose statement or mapped requirement requires knowing what the agent did, report
  `response_depends_on_actions`. This includes a reply whose truth has to be checked against what the agent did.
  The judge can decide what an assistant message says from the message itself.
- For an `actions` assertion whose statement can be decided from the replies alone, report
  `actions_depends_only_on_replies`. Asking what a reply says, including whether it gives a sufficient explanation,
  can be a reply-only check.
- For an `actions` assertion that asks whether something happened and a CAT built-in tool, or a check program, can
  decide that part from the trial record, report `action_decidable_by_tool`. This includes a statement that combines
  an observable happening with a judgment about meaning. If a separate assertion's built-in tool checks the happening
  and this judged assertion checks only meaning, such as the adequacy of a brief in a tool input, the split already works.
- Use `no_finding` when none of those decisions applies. Where a check needs both kinds of evidence or its scope is
  unclear, `actions` is appropriate.

CAT's built-in tools for actions are `file-exists <path>`, `file-absent <path>` and `file-content <path> <text>`
for the final project, `changed-only <path>...` for allowed changes, `tool-used <tool> [<input-regex>]`,
`tool-unused <tool> [<input-regex>]` and `no-tool-call` for tool calls, `exit-status <command-regex> <status>` for a
command's result, and `read-in-full <path>` and `read-at-most <path> <percent>` for how much of a file reached the
agent. When no built-in tool above observes the fact that shows the action happened, a check program can decide it
from the trial's final project, activity and replies, such as a configuration file that parses and names a new
service or an edit that leaves every other entry of a file as it was; report `action_decidable_by_tool` with
`check-program checks/<file>` in `tool`. When a built-in tool above decides it, put that tool with representative
arguments in `tool`. Quote arguments containing spaces and use placeholders for values that vary by case. For every other decision put null in `tool`.

Return one JSON object matching the result schema. Include one row for each judged assertion and give the concrete
reason for its decision. Cite a statement, requirement, or evidence boundary as needed. Mechanical assertions supply
context and receive no rows.

## Result schema

{{result_schema}}

## Test assertions

<assertions>
{{assertions}}
</assertions>
