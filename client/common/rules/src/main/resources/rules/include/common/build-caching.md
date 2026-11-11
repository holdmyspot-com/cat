# Build and Workflow Caching

## Design Goals

- Reuse completed build or workflow outputs only when their determining context and covered scope remain valid.
- Maintain useful caches during routine work without substituting cached results for required clean acceptance.
- Build the test artifacts of one revision once when its tests run more than once, instead of rebuilding them for each
	run.
- Rely on a cache whose key the agent defines only after a run from the cache and a run without it gave the same
	results.

## Guidance

When running a build or selecting, configuring, or reusing a build or workflow cache, look for and maintain supported
cache facilities so identical work can reuse valid outputs across attempts and scopes. Prefer existing facilities and
keep them enabled during routine work instead of clearing them on every attempt. If safe reuse cannot be established,
recompute the affected work.

Reuse only completed outputs whose determining inputs, dependencies, toolchain, and effective configuration still
match; this includes features, target, and profile when relevant. Invalidate only affected outputs and their dependents.
Distinguish individual unit outputs from aggregates covering a subset or the full scope: a partial aggregate is not a
complete result. Matching source alone does not establish matching build context.

A required clean acceptance run is an exception to reuse: it must rebuild its outputs without prior cached build
results. Preserve useful caches for routine work separately where possible; the clean gate does not justify clearing
or disabling caches on every development attempt.

When the same revision's tests run more than once, such as reruns, runs of a filtered subset, repeated stress runs or
runs on several machines, build the test artifacts once and run each repetition from them. A workflow on remote runners
starts each run on a fresh machine, so keep the built artifacts as the workflow's retained artifact or cache for the
repetitions to fetch. Rebuild only when a determining input above changes.

When you define a cache's key yourself, such as a hosted workflow's cache of compiled dependencies, make the key cover
every input that can change the cached output, such as the lockfile, the toolchain and any applied patches. When you
create the cache or change its key, run the workflow once from the cache and once without it on the same revision. Before
comparing their results, lazy load `run-comparisons.md`. Rely on the cache for later runs only when both give the same
results; when they differ, the key lacks an input, so add it or stop using the cache. A cache that the build tool keys
and validates itself needs no such comparison.
