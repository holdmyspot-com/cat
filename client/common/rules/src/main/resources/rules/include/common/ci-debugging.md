# CI and Hosted-Workflow Debugging

## Design Goals

- Select the fastest available reproduction boundary that can distinguish a suspected CI or hosted-workflow failure
  without treating a locally different execution as evidence of the hosted result.
- Preserve hosted acceptance evidence when the required platform, identity, artifact, or service boundary is unavailable
  locally or is part of the required outcome.

## Guidance

When debugging a failed CI or hosted-workflow step, identify its executed command, determining inputs, consumed artifact,
harness configuration, and platform before choosing where to investigate. If a supported local invocation can exercise
that same relevant boundary from retained or reproducible inputs, run it locally before dispatching a replacement hosted
run. Use its result to narrow the cause and repair the responsible source; a passing local run does not establish that a
hosted-only boundary passed.

Use retained CI evidence first when the failure depends on a runner platform, hosted identity or permission, protected
input, attestation, live service, or other boundary that the local invocation cannot exercise. Preserve the failed
receipt, log, and artifacts, identify the smallest supported hosted check that can distinguish the remaining cause, and
do not substitute a similar local command for that check.

After a local repair, run the required hosted workflow again whenever its CI-specific result remains an acceptance gate.
Record local reproduction and hosted verification as separate evidence, each scoped to the boundary it actually
exercised.

When debugging a GitHub Actions workflow, lazy load `ci-debugging/github-actions.md` before dispatching a diagnostic
run or changing its job selection.
