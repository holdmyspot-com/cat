# Rule Organization

## Design Goals

- Let readers find the guidance for one durable rule, prompt, skill, command, or workflow decision without unrelated
  topics obscuring it or forcing unnecessary routing layers.
- Keep each rule or prompt requirement with its authoritative owner and make every route actionable from an observable
  condition.
- Keep rule and prompt changes focused, nonduplicative, and verifiable across their complete applicable corpus.

## Guidance

Before adding a rule, prompt, skill, command, or workflow, or amending an existing one, inventory the available
owners. Compare the proposed guidance's observable trigger, reader decision, Design Goal, and required evidence with
each plausible owner; different names or examples do not make the guidance distinct. Amend an existing owner only when
it protects the same reader decision. Otherwise create a focused companion and add a narrow route from the rule or index
that detects its observable trigger. Do not turn a routing index into a policy rule, or broaden a focused policy merely
because its readers also encounter the new concern. Verify that every resulting artifact has one coherent long-lived
responsibility, and that a new artifact has its own title, Design Goal, trigger, acceptance evidence, and no duplicate
route or contract.

When a failure suggests a rule or prompt amendment, identify the reader action that would have prevented the failure
before using a downstream report, estimate, log, test result, or warning to choose its owner. Compare the amendment with
the receiving artifact's existing Design Goals and reader decision. If it governs a distinct action, create or select
the focused action artifact instead. Record this comparison in the design checkpoint before editing.

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

When a focused companion has one authoritative parent rule or prompt, express that ownership in its path: place it in a
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
guidance, keep a removable common core complete and actionable without every companion. Put each specific requirement,
example, exception, and routing instruction in the relevant companion. Choose each companion's narrowest delivery path:
use minimal `paths` frontmatter when paths identify the trigger; otherwise use an eagerly loaded index that names the
activity or decision and lazy-loads the focused file. Before handoff, conceptually remove each companion and verify that
the core has no undefined terminology, dangling reference, incomplete procedure, or dependent example. Verify that each
companion adds only its focused context and does not redefine, contradict, or silently depend on another companion.

When harness wrappers include one shared prompt body, place every normative instruction in that body unless a documented
harness difference changes its reader, trigger, decision, or observable result. Identical additions to two wrappers are
evidence that the shared body owns them. Do not duplicate an instruction merely to place it after an include; move it to
the corresponding position in the shared body so every assembled harness artifact receives it once.

Add or retain repository-maintained guidance only for project-specific conventions, explicit user decisions, or
constraints whose correct application depends on the project's architecture, artifacts, or workflow. Do not add a rule
that merely restates baseline language, standard-tool, or general engineering knowledge; correct that violation in the
implementation or review instead.

After adding or changing a rule or prompt, review the resulting applicable corpus before handoff:

- Every local lazy-load target and referenced rule file exists.
- Every external-file reference names only the file and its purpose; its mutable values remain in the authoritative file.
- Routing is unambiguous: no duplicate top-level rule, contradictory instruction, or overlapping path rule changes the
  intended guidance unexpectedly.
- Rule examples comply with the rules they demonstrate.
- Removed or renamed rules have no stale references.

Resolve conflicts by keeping one authoritative rule and routing to focused companions rather than duplicating guidance.
