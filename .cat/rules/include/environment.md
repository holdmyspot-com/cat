## CAT Environment Access

## Design Goals

- Centralize CAT environment access behind explicit project abstractions.
- Ensure each CAT value used by a CLI or hook derives from its harness's documented native environment contract and
	cannot be overridden by a locally defined CAT environment variable.

## Guidance

At a harness process boundary, derive every CAT value from the native environment variables guaranteed by that harness's
documented contract. A locally defined `CAT_*` variable must never override a derived value. Before proceeding, reject a
defined `CAT_*` variable unless the harness derives the same value; report the conflicting variable and native source in
the diagnostic.

Classify an environment-variable reference by the process that reads it before adding code or instructions. A variable
documented only for a hook invocation may be used only by that hook entry point and its hook-specific instructions; it
is not available to an ordinary agent tool shell, runner, or standalone CLI. Those other contexts must use the native
values documented for their own process or receive the resolved value as an explicit argument. Do not infer that a
variable available in one harness process is available in another process merely because both participate in the same
workflow.

Pass resolved values through the owning scope. Do not call `System.getenv()` outside the harness process boundary or
let unrelated code read CAT environment variables directly. Test each harness entry point with its native value alone,
with a
matching `CAT_*` value, and with a conflicting `CAT_*` value; also verify that a missing native variable produces its
actionable diagnostic. When a workflow spans more than one process context, test the variable where it is documented as
present and where it is documented as absent, then verify the absent context uses its own native value or explicit
argument instead.
