# GitHub Actions Diagnostic Scope

## Design Goals

- Make a GitHub Actions diagnostic run exercise the failed boundary without consuming runner minutes on independent
	expensive jobs.
- Preserve the complete platform matrix and all required gates as release-acceptance evidence rather than silently
	weakening them during diagnosis.

## Guidance

Before dispatching a GitHub Actions diagnostic run, identify the failed step, its direct job dependencies, and the
inputs it actually consumes. Run only those jobs and platforms needed to reproduce that boundary. Skip, disable, or
avoid dispatching an expensive independent step—such as a macOS build—when neither the failed step nor its inputs
depend on it.

Use a focused diagnostic workflow or job when the release workflow's complete matrix would otherwise run. Retain the
failed command, determining inputs, platform, and diagnostic logs in that focused path. A successful diagnostic run is
only repair evidence: after a repair, run the complete required GitHub Actions workflow, including every platform and
terminal gate, before claiming the release gate passes.

Do not skip a platform when its produced artifact, receipt, or platform behavior is an input to the diagnostic
boundary. Do not remove required release jobs or redefine the release matrix merely to reduce diagnostic cost.
