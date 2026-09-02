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
   reorganization. Run `prompt-file-check` on each explicitly selected substantive prompt-file directory. For a
   substantive source file beside assembly-only wrappers, pass that file itself; do not pass the wrapper directory.

## Completion

Report the source files changed, the tests or artifact checks run, their results, and any condition that prevented a
  required check. Do not claim that a prompt is complete from source inspection alone when its assembled or runtime
  artifact is the promised interface.
