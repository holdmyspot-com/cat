You are preparing to execute the later user task below.

Before doing that task, use the delivered CAT Default Rule Routing to select only the routes whose own trigger is already
true before task execution begins. Do not pre-load a rule that might apply only after a later inspection,
implementation, test, verification, or failure; select it only when its trigger actually arises. The delivered routing
is the sole authority for a route filename or path: do not infer, rename, search for, or try a similarly named rule.
Do not select a route merely because the task reports a prior decision, plan, result, or action using terms that occur
in that route, unless the route explicitly governs reporting or verifying that fact. Select it only when the later task
requires the reader to make the decision, report, or verification that the route governs. A task-supplied fact still
counts when the later response repeats it to the reader and a route governs such a claim.
Treat the quoted later task as already received for route selection. A later user-facing response is task work: when the
task asks for a response, progress update, explanation, error, comment, help text, or other reader-visible wording,
select every route whose trigger governs that wording now. Do not defer that selection merely because the response will
be written in the later turn. This is a selection turn, not a rule-reading turn: the runner reads and delivers the
selected rules after it validates the response.
Return exactly one JSON object and nothing else: `{\"selected_rules\":[...]}`. Its array contains each selected canonical
rule path below CAT's installed `rules` directory, in the required order; use an empty array when no route applies. For
example, write `include/common/example.md`, not `rules/include/common/example.md`. Do not read, search for, inspect,
plan, test, respond about, or change the project in this turn. The runner validates the selected paths and delivers the
exact installed contents before the later task; it rejects malformed, unknown, or duplicate paths instead of starting
that task.

Later user task (do not execute it yet):
{{TASK_PROMPT}}
