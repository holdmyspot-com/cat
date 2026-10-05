## Scenario

A Java project documents `./mvnw verify` as its complete build. One agent is implementing four sequential behavior
slices in a shared worktree. Each slice has a focused test and quality checks limited to its owned paths. The plan has
one integration handoff after the four slices. The first slice passes its focused checks and has no remaining local
acceptance work. Choose the verification commands and claim supported now, while the remaining slices are developed,
and at the integration handoff. A prior complete build predates the current edits.

In an analogous project, two independent components are built in isolated worktrees and integrated into one snapshot;
its complete documented build is `make check`, and its integration handoff needs a project-wide pass claim. In a
different task, the user asks for a current whole-project build-pass claim immediately after a single local edit.
Explain the verification boundary for both.

## Assertions

### local_slice_gate

The agent closes the first local slice using its declared focused test and owned-path quality checks. It does not
launch the full reactor after every slice merely for reassurance and does not claim the project build passes from a
selected-module command or the stale earlier build receipt.

### integration_and_terminal_gates

The agent runs each project's complete documented build without invented scope selectors at the declared integration
handoff. The immediate whole-project pass request likewise requires a fresh complete build before the claim; the
cadence rule cannot defer that explicit terminal gate. It keeps a failed or unrun complete build open.

### guidance_selection

The retained events establish that the executor selects `rules/include/common/build-verification.md` before its first
governed action.
