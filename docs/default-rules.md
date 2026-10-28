# CAT Default Rules

CAT supplies these bundled common rules for every supported coding harness. Most are injected directly at session start;
backward-design and design-quality guidance is loaded when design or implementation work needs it, and remote-runner
testing guidance when a task tests changed commands on a remote runner. Add project rules only for decisions that are
specific to your codebase or workflow.

| Rule | What it provides |
| --- | --- |
| Audience-appropriate language | Clear, reader-focused wording for user-facing and developer-facing text. |
| Backward design | Outcome-first design and iterative generalization.<br>Loaded before recommending, designing, implementing, or reviewing a change. |
| Class design | Focused component responsibilities, safe API choices, narrow collaboration boundaries, and test-support ownership. |
| Configuration readability | Blank-line grouping for supported configuration files. |
| Commit messages | Observable, typed Git commit messages. |
| Contract retirement | Complete retirement of supported contracts while preserving retained contracts. |
| Default rules | Routing to focused guidance when a task needs it. |
| Design quality | Focused responsibilities and behavior-focused tests.<br>Loaded before recommending, designing, implementing, or reviewing a change. |
| Documentation precision | Precise documentation for behavior, order, replacement, and destructive effects. |
| Environment access | Resolve environment values at process boundaries and inject them into application code. |
| Error handling | Specific failures, safe diagnosis, and actionable recovery guidance. |
| External bug workarounds | Evidence and scope requirements for temporary upstream-defect workarounds. |
| Failure analysis and prevention | Diagnose reported failures, correct their source cause, and verify prevention. |
| Intermediate versus terminal goals | Keep intermediate work distinct from terminal workflow completion. |
| Java exception semantics | Appropriate Java exception types for invalid arguments and unsupported capabilities. |
| Limitations and workarounds | Evaluate feasible paths before declaring a limitation or choosing a workaround. |
| Prompt-file references | Concise, authoritative references between prompts, files, and tools. |
| Prompt-file dependencies | Dependency ordering and term definitions across rendered direct rules. |
| Remote runner testing | A live, authenticated Upterm session to the worker before changed commands run there, retries on the same worker, and observed cleanup, on any runner provider.<br>Loaded when a CI or hosted workflow runs changed commands on a remote runner to test them; the `remote-runner-session` skill supplies the procedure. |
| Reusable values | One authoritative definition for values reused across coupled locations. |
| Test-driven development | Focused tests before implementation for testable changes. |
| Testing focus and isolation | Observable, isolated tests that avoid validating implementation mechanics. |
| User request scheduling | Acknowledge and prioritize arriving user requests predictably. |
| Warning suppression | Fix warnings at their source before narrowly suppressing an unavoidable warning. |

CAT also supplies a SessionStart rule for the active harness. It establishes that session context comes from CAT's
bundled rule directories, the user's `~/.cat/rules` directory, and the project's rule directories. See
[CAT Rules](project-rules.md) for the locations and loading behavior.
