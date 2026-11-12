# Build and Workflow Caching

## Design Goals

- Reuse completed build or workflow outputs only when their determining context and covered scope remain valid.
- Maintain useful caches during routine work without substituting cached results for required clean acceptance.
- Build the test artifacts of one revision once when its tests run more than once, instead of rebuilding them for each
	run.
- Build an expensive dependency that many jobs of a workflow need once per platform instead of in every job, with no job
	blocked or failed by a missing or failed producer or cache.
- Key a cache the agent defines on the machine and inputs that determine its outputs, and keep it to the outputs worth
	caching.
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
every input that can change the cached output, such as the lockfile, the toolchain and any applied patches. Also:

- Include every committed input that can change the cached outputs, such as build configuration files, vendored or
	patched trees, and the workflow definition itself.
- When the machine supplies the toolchain and the build does not pin it, include the machine image or the toolchain and
	SDK versions in the key, such as a compiler's install path and SDK version. A pinned toolchain needs only its pinned
	version.
- Fall back to a lane's most recent entry only when the build tool validates each cached output itself, such as by a
	content key per action, so that a stale entry costs only misses and never wrong outputs.
- Keep the cache small: cache the expensive kinds of outputs, not everything.

When you create the cache or change its key, run the workflow once from the cache and once without it on the same
revision. Before comparing their results, lazy load `run-comparisons.md`. Rely on the cache for later runs only when
both give the same results; when they differ, the key lacks an input, so add it or stop using the cache. A cache that
the build tool keys and validates itself needs no such comparison.

When many jobs of a workflow, such as every shard, platform or build configuration, each build the same expensive
dependency, build it once per platform instead:

- A dedicated producer step per platform builds it and stores the outputs in a shared cache that the consuming jobs
	read. Consumers only read the cache. When the producer or the cache is missing or failed, they build the dependency
	themselves and neither fail nor wait.
- The producer uses exactly the same build options, toolchain, and target labels or paths as the consumers; any
	difference changes the outputs or the cache keys, and the cache never hits. When the consumers reach the dependency
	through a configuration change, build it through that path, not directly.
- A dependency with independent parts, such as host tools and target libraries, may be produced in parallel, each part
	with its own cache entry, and the consumers combine the entries locally.
- Before the heavy work, the producer checks whether the exact cache entry exists and skips the work when it does.
