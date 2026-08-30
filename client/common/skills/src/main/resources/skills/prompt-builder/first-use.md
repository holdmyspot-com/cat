# Prompt Builder

## Design Goals

- Turn a requested prompt artifact change into an observable, source-of-truth behavior with evidence that an intended
  reader can load and follow it.
- Keep one prompt decision in its authoritative file while giving independently triggered companion decisions focused,
  reachable instructions.
- Detect unsupported prompt claims, ambiguous routing, and regressions with tests that can fail for the relevant
  behavior.
- Solely own prompt design discipline: backward design, Design-Goal auditing, prompt-file organization and revision,
  deterministic checks, assembly/build validation, and formal certification.

## Workflow

1. State the requested reader, trigger, terminal outcome, and acceptance evidence. Distinguish the prompt artifact from
   its generated, installed, or runtime form. If the request changes an existing artifact, inventory its retained
   obligations before proposing a revision.
   When any workflow step needs a temporary workspace, diagnostic, generated manifest, or other uncommitted artifact,
   derive the active harness's `CAT_WORK` and use its `workflow-temp create <workflow-name>` launcher to allocate one
   fresh invocation directory. Pass that directory, or a fresh child that the receiving CLI creates and owns, to every
   cooperating command. Do not reconstruct the CAT work root or use `/tmp`, a default system temporary directory,
   or another shared location. At the workflow's terminal boundary, use `workflow-temp delete` or `workflow-temp
   handoff` according to the retained artifact's recorded last consumer.
   When `prompt-optimizer` supplies a handoff, use its retained observed-run evidence, failure classification, earliest
   mistaken decision, prevention goals, affected scope, and terminal gates as the request inputs. Return the revised
   artifact, Design-Goal and ownership audit, deterministic-check results, assembled/build evidence,
   formal-certification result, and any blocker so the optimizer can perform a fresh independent retest.
2. Read [design.md](design.md). Derive the smallest executable procedure backward from the terminal evidence. Before
   assigning any step to prompt prose, make the prompt-versus-CLI operation ledger required there and move every
   deterministic step to a project-owned CLI or extend the CLI that owns it. Compare the concrete request with one
   meaningful analogous case and one dissimilar case before choosing the skill's scope.
3. Read [organization.md](organization.md). Locate the authoritative source for every decision. Amend it only when the
   decision has the same trigger and reader; otherwise create a focused companion and a direct route to it.
4. Read [assembly.md](assembly.md), [engine-neutrality.md](engine-neutrality.md), and
   [frontmatter-and-includes.md](frontmatter-and-includes.md) when the change ships to more than one harness or changes
   a prompt file's metadata, loading, or included content.
5. Write the prompt in the vocabulary and capabilities available to its actual reader. A shared body must use
   harness-neutral capability language; put harness-specific metadata and invocation details in that harness's wrapper.
6. Read [compression.md](compression.md) after preserving obligations and before accepting a material rewrite or
   reorganization.
7. Read [validation.md](validation.md), [test-cases.md](test-cases.md),
   [model-effort-policy.md](model-effort-policy.md), [evaluation-terminology.md](evaluation-terminology.md), and
   [formal-sprt.md](formal-sprt.md). Add
   durable tests for the main request, its nearest boundary, and a test that must not invoke the prompt. Validate
   the suite and run the applicable formal evaluation before accepting variable executor behavior. Then build or
   assemble every published target the prompt supports. Run `prompt-file-check` on each explicitly selected substantive
   prompt-file directory. For a substantive source file beside assembly-only wrappers, pass that file itself; do not
   pass the wrapper directory. When source-code comments or Javadoc are in scope, also run
   `source-documentation-inventory` for the selected source roots.
8. Read [model-profile.md](model-profile.md) only when the request proposes a model or reasoning-profile change.
9. When a test fails, read [failure-revision.md](failure-revision.md) and [failure-closure.md](failure-closure.md).
   Diagnose the first decision that could have prevented the observed result, revise that owner, and rerun the affected
   test with fresh evidence.
10. For a delivered, adequate prompt that an executor misapplies, read [hardening.md](hardening.md) and
    [review-handoffs.md](review-handoffs.md) before requesting independent challenge. Use [output.md](output.md) for
    every progress and completion handoff.
11. Read [evidence-record.md](evidence-record.md) whenever a run result, failure, or acceptance decision must be
    handed to another reviewer or a later retry.

## Completion

Report the source files changed, the tests or artifact checks run, their results, and any condition that prevented a
  required check. Do not claim that a prompt is complete from source inspection alone when its assembled or runtime
  artifact is the promised interface.
