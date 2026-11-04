# Build and Workflow Caching

## Design Goals

- Reuse completed build or workflow outputs only when their determining context and covered scope remain valid.
- Maintain useful caches during routine work without substituting cached results for required clean acceptance.

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
