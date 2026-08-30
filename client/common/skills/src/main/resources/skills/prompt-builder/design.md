# Prompt Design

## Design Goals

- Turn a requested prompt outcome into an ordered procedure whose reader, trigger, action, and completion evidence are
  explicit.
- Generalize prompt guidance only when the original case, an analogous case, and a dissimilar case support the same
  reader decision.
- Move deterministic prompt-workflow operations to a project-owned tool while retaining contextual judgment in the
  prompt guidance that selects and interprets that tool.
- Prevent prompt prose from becoming an alternate manual procedure for a repeatable workflow.

Start from a terminal reader-visible outcome and the evidence that demonstrates it. Repeatedly ask what a reader must
know, decide, or be able to do for that outcome to occur, until each condition is either provided by the request or can
be created directly. Reverse those conditions into ordered instructions.

For each instruction, name an observable trigger, the reader action, the resulting state, and evidence that tells the
reader whether to continue, stop, or report a blocker. Do not replace a missing condition with an aspiration such as
“keep it safe” or “make it robust.”

Before generalizing a prompt, compare the reported case with a meaningful similar case and a relevant dissimilar case.
Retain the narrowest shared obligation that still directs the original case. Examples illustrate an obligation; they do
not silently define its scope.

Before writing or revising a prompt workflow, create an operation ledger. For every input, output, state transition,
validation, formatting operation, file or Git operation, subprocess argument, path, temporary resource, recovery
check, and follow-up diagnosis, record whether the prompt or a CLI owns it and why. A ledger item may stay in the prompt
only when it needs contextual judgment, user authority or preference, genuinely novel text, or a decision that cannot
be expressed as structured CLI input. Everything else belongs to a project-owned CLI. If a deterministic operation has
no suitable command, add or extend that command before accepting prompt prose that asks a reader to perform it.

Make each CLI own the complete mechanical transaction: derive safe values, validate its preconditions and results,
create and clean up its resources, preserve failure evidence, and return the facts that the next prompt decision needs.
The prompt selects the command and interprets its structured result; it does not recreate paths, arguments, validation,
or recovery steps. Reinspect the rendered prompt after the split. Every remaining reader action must name the judgment
or authority that keeps it out of the CLI.
