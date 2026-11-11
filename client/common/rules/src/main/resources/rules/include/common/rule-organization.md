---
depends-on:
  - ./contract-design.md
---
# Rule Organization

## Design Goals

- Let readers find the guidance for one durable rule, prompt, skill, command, or workflow decision without unrelated
	topics obscuring it or forcing unnecessary routing layers.
- Keep each rule or prompt requirement in its governing prompt file and make every route actionable from an observable
	condition.
- Keep rule and prompt changes focused, nonduplicative, and verifiable across their complete applicable corpus.
- Load each prompt file in every situation where its Design Goals would help the agent about to act, and in no other.
- Keep the coverage of prompt files mutually exclusive, so at most one prompt file governs any given decision, and
	have the user settle a conflict whose resolution changes what a reader decides.
- Apply these checks whenever any prompt file is added or updated, whatever its kind or the size of the edit.

## Guidance

Before adding a rule, prompt, skill, command, or workflow, or amending an existing one, select its governing prompt
file by the comparison that the contract-design rule requires. When no existing prompt file governs the decision, create
a focused companion and add a narrow route from the rule or index that detects its observable trigger. Do not turn a
routing index into a policy rule, or broaden a focused policy merely because its readers also encounter the new concern.
Verify that every resulting artifact has one coherent long-lived responsibility, and that a new artifact has its own
title, Design Goal, trigger, acceptance evidence, and no duplicate route or contract.

When a failure suggests a rule or prompt amendment, identify the reader action that would have prevented the failure
before using a downstream report, estimate, log, test result, or warning to choose its governing prompt file. Compare
the amendment with the receiving artifact's existing Design Goals and reader decision. If it governs a distinct action,
create or select the focused action artifact instead. Record this comparison in the design checkpoint before editing.

Do not place command-selection or command-scope guidance in a workflow-status or terminal-goal rule merely because the
command supplies acceptance evidence. Compare a full-build verification case, where the reader must choose the complete
project command, with a non-build terminal gate such as a hosted receipt: only the former needs command-scope guidance.
Place the former in build-verification guidance and leave the terminal-goal rule to classify evidence and completion.

Do not assign guidance to a workflow rule merely because the reported failure occurred while that workflow was running.
Name the decision the guidance governs, then compare a case that needs that decision outside the proposed parent and a
case inside the parent that does not need it. Make the guidance independent when the first case still needs it; retain it
under the parent only when its decision depends on the parent's distinct state or contract. For example, preserving
verified progress across rounds belongs to monotonic-progress guidance, while reusing one operation identity after an
unknown request outcome belongs to idempotency guidance even when both occur in one workflow.

Give each skill, command, or workflow one primary decision contract. Split it when its alternatives have different
invocation evidence, required inputs, placement or safety rules, irreversible actions, or verification outcomes. Do not
hide such alternatives behind a mode selector. Keep alternatives together only when they differ in a mechanical
parameter while preserving the same decision contract and verification.

When a rule or prompt file contains independently triggered areas, split them into focused companion files and keep the
top-level file as a concise routing index. For every prose route, including each companion route, state the observable
task condition that requires the target and use an imperative instruction such as “When …, lazy load …”. Route one
observable trigger directly to its final focused rule; retain an intermediate index only when it supplies an
independently applicable decision contract. State another file as a prerequisite only when the reader can reach the
route without already loading that file through the same index; otherwise, name only the additional file. `paths`
frontmatter may supply the trigger only when the path pattern alone identifies that condition. Keep the trigger separate
from the required outcome: state the outcome in the required action, outcome, or Design Goals instead. Keep material
together when it has the same trigger, decision contract, and verification; do not create a companion merely to
separate mechanical variants or short related details.

When a focused companion has one governing parent rule or prompt, express that relationship in its path: place it in a
directory named after the parent filename without `.md`, and name the companion for its distinct decision. For example,
the companion for `write-clearly.md` belongs at `write-clearly/unexpected-result.md`, not beside its parent under a
compound name such as `write-clearly-unexpected-result.md`. The directory lets a reader find the parent contract
before its narrower case without guessing, and makes routes, tests, and future companions straightforward to inventory.
Keep an independently discoverable rule at the existing common root when it has no single parent or its path would
falsely imply one. After a move, update every route, test expectation, and assembled-artifact reference; a file move is
incomplete while any of those still names the former path.

Name each rule or prompt file for its primary long-lived responsibility. Rename it only when its current name materially
misdescribes that responsibility for intended readers; do not rename it merely because its wording, examples, subrules,
or implementation details changed. Choose the broadest accurate foreseeable name that does not mislead a reader looking
for the artifact's main enduring job.

When creating or restructuring a rule or prompt with language-, tool-, syntax-, ecosystem-, or harness-specific
guidance, keep a removable common core complete and actionable without every companion. A specific companion covers only
what the core does not: a requirement, exception, or decision that differs for its language, tool, syntax, ecosystem, or
harness, or that the core cannot state in neutral terms. Put each such item, with its examples and routing instruction,
in the relevant companion. Do not restate a core requirement there, translate it into that context's syntax, or add an
example of it; when no such item remains, keep the guidance in the core and do not create the companion. Choose each
companion's narrowest delivery path: use minimal `paths` frontmatter when paths identify the trigger; otherwise use an
eagerly loaded index that names the activity or decision and lazy-loads the focused file. Before handoff, conceptually
remove each companion and verify that the core has no undefined terminology, dangling reference, incomplete procedure,
or dependent example. Verify that each companion adds only its focused context and does not redefine, contradict, or
silently depend on another companion.

When harness wrappers include one shared prompt body, place every normative instruction in that body unless a documented
harness difference changes its reader, trigger, decision, or observable result. Identical additions to two wrappers are
evidence that the shared body owns them. Do not duplicate an instruction merely to place it after an include; move it to
the corresponding position in the shared body so every assembled harness artifact receives it once.

Add or retain repository-maintained guidance only for project-specific conventions, explicit user decisions, or
constraints whose correct application depends on the project's architecture, artifacts, or workflow. Do not add a rule
that merely restates baseline language, standard-tool, or general engineering knowledge; correct that violation in the
implementation or review instead.

Run every check in this rule, and the goal-to-guidance check in the contract-design rule, whenever any prompt file is
added or updated: a rule, route index, skill, agent definition, command, workflow definition, or other prompt text that
CAT ships. A small edit is not exempt. Do not finish the change while a check has an unrepaired defect or an unanswered
conflict question.

Derive each prompt file's triggers backward from its Design Goals. For each goal, list the observable situations in which
an agent about to act could benefit from guidance that achieves it: the request, the artifact it reads or edits, the
operation it is about to run, or the state present before that action. Compare that list with every trigger of the
file: its own applicability statement, such as `paths` frontmatter or a stated condition, and each route that loads it
from an index, an always-loaded rule, a skill, or another prompt file. A trigger that fires where no goal applies, or a
listed situation that no trigger covers, is a defect. Repair it by changing the trigger; change a goal only when the
list shows that the goal misstates the outcome the file exists to achieve. Derive the list again whenever a goal changes.

Make the coverage of prompt files mutually exclusive. Inventory the other prompt files whose triggers can fire in a
situation where a trigger of the added or changed file fires, and compare the decision each file governs there. Files
that govern different decisions may share a situation, provided each file's trigger or Design Goals say which decision
it governs. Files that govern the same decision duplicate each other when they require the same action and conflict
when they require different actions.

Resolve a conflict yourself only when it is trivial: the files differ only in wording, one repeats the other, or one
states a specific case of the other's action, and exactly one resolution preserves every required action and Design
Goal of both. Keep the authoritative statement and replace the other with a route or delete it. Treat every other
conflict as not trivial, in particular one where the files require different actions for the same decision or where
resolving it would remove or weaken an obligation of either file. Edit neither file for it. Ask the user which
resolution to apply, as the questions-for-the-user rule describes: name the files and the decision, the conflicting
actions, and each resolution option with its consequences and your recommendation. Continue only work that does not
depend on the answer.

After the resolution, adjust the triggers so that, for any situation, at most one prompt file governs a given decision.
State in each file's trigger the condition that puts the decision on its side of the boundary, and exclude the other
file's side, for example by artifact, operation, or state. Verify the boundary with one situation on each side and one
situation where the former triggers both fired.

After adding or changing a rule or prompt, review the resulting applicable corpus before handoff:

- Every local lazy-load target and referenced rule file exists.
- Every external-file reference names only the file and its purpose; its mutable values remain in the authoritative file.
- Routing is unambiguous: no duplicate top-level rule, contradictory instruction, or overlapping path rule changes the
	intended guidance unexpectedly.
- Every trigger of each added or changed prompt file matches its Design Goals, and no other prompt file governs the same
	decision.
- Rule examples comply with the rules they demonstrate.
- Removed or renamed rules have no stale references.

Resolve a trivial conflict by keeping one authoritative rule and routing to focused companions rather than duplicating
guidance; resolve any other conflict as described above.
