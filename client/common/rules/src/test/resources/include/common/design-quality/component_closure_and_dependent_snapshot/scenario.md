# Component closure and dependent snapshot

## Variables

- **bypass_method**: The name of the public store method that publishes a document without approval, such as
  `publishDraft`

## Scenario

Review this implementation plan for a resumable document-publishing service. Its contract requires an approved
document to survive restart, prevents an unapproved document from reaching the public endpoint, and requires a durable
publication receipt. Identify which components can be called complete and which implementation work can begin now.

- The state component's focused and combined tests pass, but its public store still has a direct `{{bypass_method}}` method
  that bypasses approval. Its persisted record also has no publication-receipt reference, and no test restarts from a
  committed publication.
- The endpoint component's isolated worktree was created before the state component added a new receipt type. Its new
  endpoint test does not compile against that older snapshot. The state change is integrated in the shared branch and
  passes focused checks; an unrelated search-index component has its own compilable worktree and tests.
- The plan has a current endpoint API section, but an earlier review narrative still instructs implementers to use the
  pre-receipt API. The old review result has a dated receipt and remains useful as historical evidence.

## Assertions

### contract_wide_closure

- **kind**: effect
- **description**: The reviewer keeps the state component open despite its green checks, names the reachable approval
  bypass through `{{bypass_method}}`, missing durable receipt field, and restart evidence, and asks for checks at the
  boundaries that prove each assigned obligation. It preserves the passing evidence for unaffected behavior.

### integrated_compilable_dependency

- **kind**: effect
- **description**: The reviewer does not count the endpoint test's compilation failure as a behavioral red result or
  start dependent implementation from that older tree. It calls for a fresh endpoint snapshot containing the integrated
  state change, then focused and combined handoff checks. It allows the independent search-index work to proceed now.

### one_current_design

- **kind**: effect
- **description**: The reviewer keeps the old review receipt as provenance but removes or clearly retires its
  pre-receipt API instruction from the executable plan. The current API section remains the single implementation
  instruction.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/design-quality.md`
  before its first governed action.
